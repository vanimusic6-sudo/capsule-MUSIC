package com.nikhil.yt.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService

/**
 * A collection is complete only when every member is complete.
 * Stop must actually transition all matching entries out of active states,
 * rather than suppressing the active status in the UI.
 */
internal fun collectionDownloadState(songIds: Collection<String>, downloads: Map<String, Download>): Int {
    if (songIds.isEmpty()) return Download.STATE_STOPPED
    val entries = songIds.distinct().map(downloads::get)
    return when {
        // Keep deletion and completion based on the real Media3 index.
        entries.any { it?.state == Download.STATE_REMOVING } -> Download.STATE_REMOVING
        entries.all { it?.state == Download.STATE_COMPLETED } -> Download.STATE_COMPLETED

        // All actual active members count. A playlist Stop targets every one
        // of them, including an overlapping priority single. Merely hiding
        // manually prioritized transfers here would mask an ongoing download.
        entries.any { download ->
            download != null &&
                download.visibleState in listOf(
                    Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING,
                )
        } -> Download.STATE_DOWNLOADING
        else -> Download.STATE_STOPPED
    }
}

internal const val COLLECTION_STOP_REASON_USER = 1

/**
 * Media3 posts the first collection ADD via DownloadService. When Stop is tapped
 * before that ADD reaches the index, setStopReason(id) is a no-op.
 *
 * Retain only the user-requested stop until the corresponding ADD is admitted,
 * then apply the stop on its first callback. One lightweight main-thread listener
 * for the process, no polling, no delay on audio downloads.
 */
internal object CollectionDownloadStopAdmission {
    private const val ADMISSION_GRACE_MS = 45_000L
    private val handler = Handler(Looper.getMainLooper())
    private var manager: androidx.media3.exoplayer.offline.DownloadManager? = null
    private val pending = mutableMapOf<String, Long>()

    private val expire = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            pending.entries.removeAll { it.value <= now }
            scheduleExpiry()
        }
    }

    private val listener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            val id = download.request.id
            if (id !in pending) return
            if (download.state == Download.STATE_COMPLETED ||
                download.state == Download.STATE_FAILED ||
                download.state == Download.STATE_REMOVING ||
                (download.state == Download.STATE_STOPPED &&
                    download.stopReason == COLLECTION_STOP_REASON_USER)
            ) {
                pending.remove(id)
                if (pending.isEmpty()) handler.removeCallbacks(expire)
            } else {
                downloadManager.setStopReason(id, COLLECTION_STOP_REASON_USER)
            }
        }

        override fun onDownloadRemoved(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
        ) {
            pending.remove(download.request.id)
            if (pending.isEmpty()) handler.removeCallbacks(expire)
        }
    }

    private fun scheduleExpiry() {
        handler.removeCallbacks(expire)
        val soonest = pending.values.minOrNull() ?: return
        handler.postDelayed(expire, (soonest - SystemClock.uptimeMillis()).coerceAtLeast(1L))
    }

    fun request(target: androidx.media3.exoplayer.offline.DownloadManager, ids: Collection<String>) {
        if (manager !== target) {
            manager?.removeListener(listener)
            pending.clear()
            manager = target
            target.addListener(listener)
        }
        val deadline = SystemClock.uptimeMillis() + ADMISSION_GRACE_MS
        ids.forEach { pending[it] = deadline }
        scheduleExpiry()
    }

    fun clear(target: androidx.media3.exoplayer.offline.DownloadManager, ids: Collection<String>) {
        if (manager !== target) return
        ids.forEach(pending::remove)
        scheduleExpiry()
    }

    /**
     * A NEW explicit one-track start after Stop overrides the stale Stop
     * intent for that track. Starts queued before Stop are unaffected because
     * request() then runs after the enqueue and recreates the pending entry.
     */
    fun clearForExplicitStart(id: String) {
        if (pending.remove(id) != null) scheduleExpiry()
    }
}

/**
 * Stop every listed collection member by default, regardless of manual
 * priority. Priority only affects scheduling, never whether Stop works.
 * Explicit callers can opt into preserving manual transfers if needed.
 * Preserve the actual cached bytes in both cases.
 */
internal fun stopCollectionDownloads(manager: androidx.media3.exoplayer.offline.DownloadManager, songIds: Collection<String>, preserveManual: Boolean = false) {
    val independent = if (preserveManual) manager.currentDownloads
        .filter { it.request.isManualDownload() }.map { it.request.id }.toSet() else emptySet()
    val ids = songIds.distinct().filterNot { it in independent }
    CollectionDownloadStopAdmission.request(manager, ids)
    ids.forEach { manager.setStopReason(it, COLLECTION_STOP_REASON_USER) }
}

/**
 * Media3 getCurrentDownloads() excludes terminal completed entries: those
 * persist only in DownloadIndex (and DownloadUtil.downloads). Filtering removal
 * to currentDownloads caused "Remove downloaded" to do nothing for completed
 * collections. Request deletion for every owned ID; Media3 safely ignores an
 * ID that was not present. Preserve manually prioritized active singles only
 * when a caller explicitly wants that policy.
 */
internal fun removeCollectionDownloads(
    manager: androidx.media3.exoplayer.offline.DownloadManager,
    songIds: Collection<String>,
    preserveManual: Boolean = false,
) {
    val protected = if (preserveManual) {
        manager.currentDownloads.asSequence()
            .filter { it.request.isManualDownload() }
            .map { it.request.id }.toSet()
    } else emptySet()
    val ids = songIds.distinct().filterNot { it in protected }
    // Deletion supersedes Stop and must not poison a subsequent fresh ADD.
    CollectionDownloadStopAdmission.clear(manager, ids)
    ids.forEach(manager::removeDownload)
}

/**
 * The shared collection start/resume operation. A Media3 user stop reason is
 * sticky: adding the same DownloadRequest alone does not reliably resume it.
 * Use the same service path in local playlists, albums and multi-select.
 */
internal fun enqueueCollectionItem(
    context: Context,
    indexed: Map<String, Download>,
    request: DownloadRequest,
) {
    val saved = indexed[request.id]
    if (saved?.state == Download.STATE_COMPLETED) return
    if (saved?.state == Download.STATE_STOPPED) {
        DownloadService.sendSetStopReason(
            context, ExoDownloadService::class.java,
            request.id, DOWNLOAD_WAITING_FOR_SLOT, false,
        )
    } else {
        DownloadService.sendAddDownload(
            context, ExoDownloadService::class.java, request, false,
        )
        if (saved != null && saved.stopReason != Download.STOP_REASON_NONE) {
            DownloadService.sendSetStopReason(
                context, ExoDownloadService::class.java,
                request.id, DOWNLOAD_WAITING_FOR_SLOT, false,
            )
        }
    }
}

/**
 * Batch start/resume for local and remote collections. Start DownloadService
 * at most once; all remaining entries go directly to the already-initialized
 * Media3 manager. This avoids one foreground-service intent per song and
 * preserves the individual priority queue for manually selected tracks.
 */
internal fun enqueueCollectionDownloads(
    context: Context,
    manager: androidx.media3.exoplayer.offline.DownloadManager,
    indexed: Map<String, Download>,
    requests: List<DownloadRequest>,
) {
    CollectionDownloadStopAdmission.clear(manager, requests.map { it.id })
    var serviceStarted = false
    val active = manager.currentDownloads.associateBy { it.request.id }
    requests.distinctBy { it.id }.forEach { request ->
        val existing = indexed[request.id] ?: active[request.id]
        when (existing?.state) {
            Download.STATE_COMPLETED -> Unit
            Download.STATE_STOPPED -> {
                // Never bypass the manual-first download admission queue.
                if (existing.stopReason != DOWNLOAD_WAITING_FOR_SLOT) {
                    manager.setStopReason(request.id, DOWNLOAD_WAITING_FOR_SLOT)
                }
            }
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING -> Unit
            else -> {
                if (!serviceStarted) {
                    DownloadService.sendAddDownload(
                        context, ExoDownloadService::class.java, request, false,
                    )
                    serviceStarted = true
                } else {
                    manager.addDownload(request, DOWNLOAD_WAITING_FOR_SLOT)
                }
            }
        }
    }
}
