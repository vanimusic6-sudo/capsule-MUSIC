package com.nikhil.yt.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Both Media3 and approved-source workers share this process-wide file-transfer limit. */
internal object AudioDownloadSlots {
    const val LIMIT = 2
    private val permits = Semaphore(LIMIT)
    suspend fun <T> withSlot(block: suspend () -> T): T = permits.withPermit { block() }
}

/**
 * A server-imposed wait must not reserve either of the process-wide file-transfer
 * permits. In particular, a throttled YouTube download must not keep an unrelated
 * Audius/local-source worker from transferring while the YouTube circuit is open.
 */
internal suspend fun <T> withAudioDownloadSlotAfterAdmission(
    awaitAdmission: suspend () -> Unit,
    block: suspend () -> T,
): T {
    awaitAdmission()
    return AudioDownloadSlots.withSlot(block)
}

// Persist scheduling intent in Media3's existing request/index, without another queue database.
// Old title-only data remains valid and is treated as a background request.
private const val MANUAL_PREFIX = "capsule-manual-v1\u0000"
internal const val DOWNLOAD_WAITING_FOR_SLOT = 2
internal fun DownloadRequest.isManualDownload(): Boolean = data.toString(Charsets.UTF_8).startsWith(MANUAL_PREFIX)
internal fun DownloadRequest.downloadTitle(): String = data.toString(Charsets.UTF_8).removePrefix(MANUAL_PREFIX)
internal fun DownloadRequest.withManualPriority(): DownloadRequest = run {
    DownloadRequest.Builder(id, uri).setMimeType(mimeType).setStreamKeys(streamKeys)
        .setKeySetId(keySetId).setCustomCacheKey(customCacheKey)
        .setData((MANUAL_PREFIX + downloadTitle()).toByteArray(Charsets.UTF_8)).build()
}

/** Single-track actions opt in; collection callers use the same service with ordinary metadata. */
internal fun enqueuePriorityAudioDownload(context: Context, service: Class<out DownloadService>, request: DownloadRequest, foreground: Boolean) {
    // A direct single-track action is a fresh user intent. Do not let a
    // previous playlist Stop intercept the new request during service startup.
    CollectionDownloadStopAdmission.clearForExplicitStart(request.id)
    DownloadService.sendAddDownload(context, service, request.withManualPriority(), foreground)
}

/** Three manual starts at most before one waiting collection member gets a turn. */
internal class AudioDownloadOrder {
    private var manualStarts = 0
    val allowPreemption: Boolean get() = manualStarts < 3
    fun next(waiting: List<Download>): Download? {
        val ordered = waiting.sortedBy { it.startTimeMs }
        val manual = ordered.firstOrNull { it.request.isManualDownload() }
        val background = ordered.firstOrNull { !it.request.isManualDownload() }
        return (if (manual != null && (background == null || manualStarts < 3)) manual else background)?.also {
            manualStarts = if (it.request.isManualDownload()) (manualStarts + 1).coerceAtMost(3) else 0
        }
    }
}

/** Main-looper admission, on top of the ONE existing Media3 manager. Manual requests can pause one collection transfer without deleting its cache. */
internal class AudioDownloadQueue(private val manager: DownloadManager) : DownloadManager.Listener {
    companion object {
        // A resumed WAITING_FOR_SLOT request may not emit any new Media3
        // callback. Explicitly nudge the existing one-per-manager scheduler.
        private val installed = WeakHashMap<DownloadManager, AudioDownloadQueue>()
        private val mainHandler = Handler(Looper.getMainLooper())
        fun wake(manager: DownloadManager) {
            // Online playlist producers resume from Dispatchers.IO. Media3
            // queue ownership and registry access remain main-thread only.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                installed[manager]?.schedule()
            } else {
                mainHandler.post { installed[manager]?.schedule() }
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val order = AudioDownloadOrder()
    private val admitted = mutableSetOf<String>()
    private val pausing = mutableSetOf<String>()
    private val protectedBackground = mutableSetOf<String>()
    private var scheduled = false
    private var closed = false

    init {
        installed[manager] = this
        manager.addListener(this)
    }
    override fun onInitialized(downloadManager: DownloadManager) {
        // Upgrade old queued entries; already running transfers continue untouched.
        manager.currentDownloads.filter { it.state == Download.STATE_QUEUED }.forEach {
            manager.setStopReason(
                it.request.id,
                if (CollectionDownloadStopAdmission.isUserStopped(it.request.id, manager))
                    COLLECTION_STOP_REASON_USER else DOWNLOAD_WAITING_FOR_SLOT,
            )
        }
        schedule()
    }
    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        // A completed/stopped legacy entry can retain the old waiting reason.
        // It is NEVER an admitted transfer occupying one of the two slots.
        if (download.stopReason != DOWNLOAD_WAITING_FOR_SLOT ||
            download.state == Download.STATE_COMPLETED || download.state == Download.STATE_FAILED) {
            admitted.remove(download.request.id)
        }
        if (download.state == Download.STATE_STOPPED || download.state == Download.STATE_FAILED ||
            download.state == Download.STATE_COMPLETED) {
            pausing.remove(download.request.id)
            protectedBackground.remove(download.request.id)
        }
        schedule()
    }
    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        admitted.remove(download.request.id)
        pausing.remove(download.request.id)
        protectedBackground.remove(download.request.id)
        schedule()
    }
    fun close() {
        closed = true; handler.removeCallbacksAndMessages(null); manager.removeListener(this)
        if (installed[manager] === this) installed.remove(manager)
        admitted.clear(); pausing.clear(); protectedBackground.clear()
    }
    private fun schedule() {
        if (closed || scheduled) return
        scheduled = true
        handler.post {
            scheduled = false
            if (!manager.isInitialized) return@post
            val entries = manager.currentDownloads
            // If the service delivered an ADD after Stop, do not award it a slot.
            val pendingStops = entries.filter {
                CollectionDownloadStopAdmission.isUserStopped(it.request.id, manager) &&
                    it.stopReason != COLLECTION_STOP_REASON_USER &&
                    it.state != Download.STATE_COMPLETED &&
                    it.state != Download.STATE_REMOVING
            }
            if (pendingStops.isNotEmpty()) {
                pendingStops.forEach { manager.setStopReason(it.request.id, COLLECTION_STOP_REASON_USER) }
                return@post
            }
            val entryIds = entries.map { it.request.id }.toSet()
            admitted.retainAll(entryIds)
            protectedBackground.retainAll(entryIds)
            pausing.retainAll(entryIds)
            val active = entries.filter(::keepActiveDownload)
                .map { it.request.id }.toSet() + admitted
            val waiting = entries.filter {
                it.stopReason == DOWNLOAD_WAITING_FOR_SLOT &&
                    it.state == Download.STATE_STOPPED &&
                    !CollectionDownloadStopAdmission.isUserStopped(it.request.id, manager) &&
                    it.request.id !in admitted
            }.toMutableList()
            // One pause per admission wave. Media3 cancels the worker and preserves partial cache;
            // the shared transfer semaphore also prevents overlap while that cancellation drains.
            if (active.size >= AudioDownloadSlots.LIMIT && pausing.isEmpty() && order.allowPreemption &&
                waiting.any { it.request.isManualDownload() }) {
                entries.lastOrNull { it.request.id in active && !it.request.isManualDownload() &&
                    it.request.id !in protectedBackground && it.request.id !in admitted }?.let {
                    pausing.add(it.request.id)
                    manager.setStopReason(it.request.id, DOWNLOAD_WAITING_FOR_SLOT)
                }
            }
            repeat((AudioDownloadSlots.LIMIT - active.size).coerceAtLeast(0)) {
                val protectBackground = !order.allowPreemption
                val next = order.next(waiting) ?: return@repeat
                if (protectBackground && !next.request.isManualDownload()) protectedBackground.add(next.request.id)
                waiting.remove(next)
                admitted.add(next.request.id) // Reserve before asynchronous Media3 state callbacks.
                manager.setStopReason(next.request.id, Download.STOP_REASON_NONE)
            }
        }
    }
}

internal val Download.visibleState: Int
    get() = if (state == Download.STATE_STOPPED && stopReason == DOWNLOAD_WAITING_FOR_SLOT)
        Download.STATE_QUEUED else state

/** Only a waiting collection member needs promotion; tapping an actual transfer pauses it. */
internal val Download.canPrioritize: Boolean
    get() = state == Download.STATE_STOPPED && !request.isManualDownload() &&
        stopReason == DOWNLOAD_WAITING_FOR_SLOT

internal fun keepActiveDownload(download: Download?): Boolean =
    download?.stopReason == Download.STOP_REASON_NONE && when (download.state) {
        Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> true
        else -> false
    }
