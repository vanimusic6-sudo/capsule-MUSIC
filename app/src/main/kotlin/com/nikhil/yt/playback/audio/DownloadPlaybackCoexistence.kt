package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * File transfers must not consume network bandwidth while the user is waiting
 * for the foreground track. This is NOT a YouTube throttle or an extractor ban.
 *
 * Observe Media3 state changes (never poll the player off its application looper).
 * A buffering episode gets a bounded head start; a READY transition gets a short
 * refill window. A stuck player does not freeze unrelated downloads indefinitely.
 */
internal class DownloadPlaybackNetworkPriority(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private enum class Phase { IDLE, BUFFERING, READY }
    private data class Snapshot(val mediaId: String?, val phase: Phase, val enteredAtMs: Long)

    @Volatile private var snapshot = Snapshot(null, Phase.IDLE, 0L)

    fun observe(mediaId: String?, playWhenReady: Boolean, buffering: Boolean, ready: Boolean) {
        val newPhase = when {
            !playWhenReady || mediaId.isNullOrBlank() -> Phase.IDLE
            buffering -> Phase.BUFFERING
            ready -> Phase.READY
            else -> Phase.IDLE
        }
        synchronized(this) {
            val previous = snapshot
            // Repeated player events must not extend the gate indefinitely.
            if (previous.mediaId == mediaId && previous.phase == newPhase) return
            snapshot = Snapshot(mediaId, newPhase, nowMs())
        }
    }

    fun clear() {
        snapshot = Snapshot(null, Phase.IDLE, 0L)
    }

    fun shouldYield(): Boolean {
        val current = snapshot
        val elapsed = nowMs() - current.enteredAtMs
        if (elapsed < 0L) return false
        return when (current.phase) {
            Phase.IDLE -> false
            Phase.BUFFERING -> elapsed < MAX_BUFFERING_YIELD_MS
            Phase.READY -> elapsed < READY_REFILL_YIELD_MS
        }
    }

    companion object {
        internal const val MAX_BUFFERING_YIELD_MS = 12_000L
        internal const val READY_REFILL_YIELD_MS = 2_500L
    }
}

internal object DownloadPlaybackCoexistence {
    val priority = DownloadPlaybackNetworkPriority()
}

/**
 * ONLY wrap the downloader's network upstream, never its offline cache or the
 * player's data source. The check happens between short reads / range opens,
 * so existing in-flight transfers are not cancelled and no new /player request
 * or Media3 DownloadManager state transition is created.
 *
 * Media3's downloader invokes this on its IO worker. Do not use this adapter
 * from a player or on the main thread: sleep here would delay audible playback.
 */
internal class DownloadYieldingDataSource(
    private val upstream: DataSource,
    private val shouldYield: () -> Boolean = DownloadPlaybackCoexistence.priority::shouldYield,
) : DataSource {
    private var readSinceCheck = CHECK_BYTES

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        waitForForeground()
        readSinceCheck = 0
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (readSinceCheck >= CHECK_BYTES) {
            waitForForeground()
            readSinceCheck = 0
        }
        val result = upstream.read(buffer, offset, length)
        if (result > 0) readSinceCheck += result
        return result
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private fun waitForForeground() = waitForForegroundDownload(shouldYield)

    private companion object {
        const val CHECK_BYTES = 64 * 1024
    }
}

/** Audius uses HttpURLConnection rather than Media3, but must obey the same network priority. */
internal class DownloadYieldingInputStream(
    input: InputStream,
    private val shouldYield: () -> Boolean = DownloadPlaybackCoexistence.priority::shouldYield,
    private val checkActive: () -> Unit = {},
) : FilterInputStream(input) {
    // Unlike the player's network, Audius downloads may begin after a long
    // resolver/cache warmup. Honor an already-buffering player before the
    // FIRST network body read, not only after downloading 64 KiB.
    private var readSinceCheck = 64 * 1024

    override fun read(): Int {
        if (readSinceCheck >= CHECK_BYTES) {
            waitForForegroundDownload(shouldYield, checkActive)
            readSinceCheck = 0
        }
        val result = super.read()
        if (result >= 0) readSinceCheck++
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (readSinceCheck >= CHECK_BYTES) {
            waitForForegroundDownload(shouldYield, checkActive)
            readSinceCheck = 0
        }
        val result = super.read(buffer, offset, length)
        if (result > 0) readSinceCheck += result
        return result
    }

    private companion object { const val CHECK_BYTES = 64 * 1024 }
}

/** Called strictly from download IO workers. Sleep is bounded to avoid wakeup-heavy busy polling. */
private fun waitForForegroundDownload(shouldYield: () -> Boolean, checkActive: () -> Unit = {}) {
    while (shouldYield()) {
        checkActive()
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Download cancelled while yielding to foreground playback")
        }
        try {
            Thread.sleep(200L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Download interrupted while yielding to playback").apply {
                initCause(interrupted)
            }
        }
    }
}
