@file:Suppress("UnsafeOptInUsageError")

/*
 * Capsule MUSIC — bounded CDN reads for VIDEO only.
 *
 * The existing Media3 ProgressiveMediaSource, VIDEO cache and AUDIO pipeline
 * remain unchanged. The wrapping DataSource delivers one continuous byte
 * stream to Media3, but requests at most 4 MiB from the VIDEO CDN at a time.
 *
 * For an unknown-length resource, the server's Content-Range response gives
 * the real file size. If the CDN ignores range requests, fall back to the
 * original source behaviour instead of silently truncating the video.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import com.nikhil.yt.playback.transport.ProgressiveRangeSource
import com.nikhil.yt.playback.transport.RangePlan
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import com.nikhil.yt.playback.transport.RangeSizePolicy

/** Resume only a peer-reset socket, never 403/410/429, DNS, TLS or a cancelled seek. */
internal fun isRecoverableVideoSocketReset(failure: IOException): Boolean =
    generateSequence(failure as Throwable?) { it.cause }
        .take(8)
        .any { cause ->
            (cause is SocketException &&
                (cause.message?.contains("connection reset", ignoreCase = true) == true ||
                    cause.message?.contains("ECONNRESET", ignoreCase = true) == true)) ||
                (cause is IOException && cause.javaClass.simpleName == "ConnectionResetException")
        }

internal fun isRecoverableVideoReadFailure(failure: IOException, rangeHasProgress: Boolean): Boolean {
    val causes = generateSequence(failure as Throwable?) { it.cause }.take(8).toList()
    if (Thread.currentThread().isInterrupted || causes.any {
            it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ||
                it is java.util.concurrent.CancellationException || it is InterruptedException ||
                (it is java.io.InterruptedIOException && it !is SocketTimeoutException)
        }) return false
    // A read timeout before a single byte has arrived is evidence of a
    // nonresponsive edge. Retrying that identical host can spend ANOTHER 8s
    // with no first frame. Peer resets are cheap to retry once, but read
    // timeouts/early EOFs are resumable only after this range made progress.
    return isRecoverableVideoSocketReset(failure) ||
        (rangeHasProgress && causes.any { it is SocketTimeoutException || it is EOFException })
}


internal const val CAPSULE_VIDEO_CHUNK_BYTES = 4L * 1024 * 1024

internal class CapsuleVideoChunkedDataSource(
    upstream: DataSource,
    chunkBytes: Long = CAPSULE_VIDEO_CHUNK_BYTES,
    currentNetworkIdentity: () -> Long? = { null },
) : ProgressiveRangeSource(
    upstream = upstream,
    planFor = { spec ->
        if (spec.length != C.LENGTH_UNSET.toLong() && spec.length <= chunkBytes) null
        else {
            val first = minOf(chunkBytes, 1024L * 1024)
            RangePlan(first, RangeSizePolicy(
                initialChunkBytes = first,
                reducedChunkBytes = minOf(chunkBytes, 512L * 1024),
                maximumChunkBytes = chunkBytes,
                slowReadMs = 30L,
                slowBps = 256L * 1024,
                recoveredBps = 1024L * 1024,
            ), requireResponseRange = true)
        }
    },
    networkIdentity = currentNetworkIdentity,
    canResume = ::isRecoverableVideoReadFailure,
) {
    init { require(chunkBytes > 0L) }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkBytes: Long = CAPSULE_VIDEO_CHUNK_BYTES,
        private val currentNetworkIdentity: () -> Long? = { null },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CapsuleVideoChunkedDataSource(upstreamFactory.createDataSource(), chunkBytes, currentNetworkIdentity)
    }
}
