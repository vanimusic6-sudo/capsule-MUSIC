@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal enum class AudioCdnRefreshReason {
    HOST_COOLDOWN,
    OPEN_FAILURE,
    READ_FAILURE,
}

/** A local recovery decision, not a fabricated HTTP response or a rejected extraction client. */
internal class AudioCdnRefreshRequiredException(
    val mediaId: String,
    val refreshReason: AudioCdnRefreshReason,
    cause: IOException? = null,
) : IOException("Audio CDN requires a fresh URL ($refreshReason)", cause)

internal fun Throwable.audioCdnRefreshRequiredOrNull(): AudioCdnRefreshRequiredException? =
    generateSequence(this as Throwable?) { it.cause }
        .take(12)
        .filterIsInstance<AudioCdnRefreshRequiredException>()
        .firstOrNull()

internal fun Throwable.requiresFreshAudioUrlFor(mediaId: String?): Boolean =
    mediaId != null && audioCdnRefreshRequiredOrNull()?.mediaId == mediaId

/**
 * A timed-out CDN open yielded no HTTP response or audio bytes. Reopening the
 * identical signed URL immediately can repeat a full TLS/read timeout on the
 * same route; for this narrow case, prefer the next configured playback client.
 *
 * Do not apply this to a slow mid-stream read, a transient peer closure, a
 * cancellation, an ordinary refused URL, or an unrelated track.
 */
internal fun Throwable.isUnresponsiveCdnOpenFor(mediaId: String?): Boolean {
    if (mediaId == null) return false
    val refresh = audioCdnRefreshRequiredOrNull() ?: return false
    if (refresh.mediaId != mediaId || refresh.refreshReason != AudioCdnRefreshReason.OPEN_FAILURE) {
        return false
    }
    return generateSequence(refresh.cause as Throwable?) { it.cause }
        .take(12)
        .any { it is SocketTimeoutException }
}

/** Keep HTTP refusal retries in the chunk layer; never turn cancellation into a fresh resolve. */
internal fun IOException.isAudioCdnTransportFailure(): Boolean {
    if (isExpectedAudioCdnInterruption()) return false
    val causes = generateSequence(this as Throwable?) { it.cause }.take(12).toList()
    if (causes.any { it is InvalidResponseCodeException }) return false
    return causes.any {
        it is SocketTimeoutException || it is SocketException ||
            it is UnknownHostException || it is EOFException
    }
}
