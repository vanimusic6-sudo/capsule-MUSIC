@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nikhil.yt.playback.audio.AudioCdnLinkIdentity
import com.nikhil.yt.playback.audio.isExpectedAudioCdnInterruption
import java.io.IOException
import timber.log.Timber

/** VIDEO-only bounded memory. No persistent URLs, credentials or AUDIO state. */
internal class VideoRejectedLinkMemory(private val nowMs: () -> Long) {
    private val entries = LinkedHashMap<String, Long>()
    @Synchronized fun reject(url: String) {
        val ref = AudioCdnLinkIdentity.ref(url)
        entries.remove(ref)
        entries[ref] = nowMs() + 600_000L
        while (entries.size > 128) entries.remove(entries.keys.first())
    }
    @Synchronized fun isRejected(url: String): Boolean {
        if (entries.isEmpty()) return false
        val ref = AudioCdnLinkIdentity.ref(url)
        val until = entries[ref] ?: return false
        if (until <= nowMs()) { entries.remove(ref); return false }
        return true
    }
}
internal object VideoRejectedLinks {
    private val memory = VideoRejectedLinkMemory { System.nanoTime() / 1_000_000L }
    fun reject(url: String) = memory.reject(url)
    fun isRejected(url: String) = memory.isRejected(url)
}
internal class VideoRetiredLinkException : IOException("VIDEO signed link was previously refused")

/** 410 may renew an expired URL once; access refusal is not recoverable. */
internal fun isVideoAccessStop(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.take(12).any {
        it is VideoRetiredLinkException ||
            (it is InvalidResponseCodeException && it.responseCode in setOf(401, 403, 429)) ||
            it is com.nikhil.yt.innertube.CapsuleVideoRequestGuard.RequestBlockedException
    }

internal fun isTerminalVideoLoadFailure(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.take(12).any {
        it is VideoRetiredLinkException ||
            (it is InvalidResponseCodeException && it.responseCode in setOf(401, 403, 410, 429)) ||
            it is com.nikhil.yt.innertube.CapsuleVideoRequestGuard.RequestBlockedException
    }

/** A valid direct/segmented stream need not be extracted again after a
 * transient I/O failure. Never retry access refusals, expired links or local
 * parsing/decoder errors under the guise of a transport restart. */
internal fun canRestartVideoTransport(error: Throwable): Boolean {
    if (isTerminalVideoLoadFailure(error) || error.isExpectedAudioCdnInterruption()) return false
    return generateSequence(error as Throwable?) { it.cause }.take(12).any {
        it is java.net.SocketTimeoutException || it is java.net.SocketException ||
            it is java.io.EOFException || it is java.net.UnknownHostException
    }
}

internal class CapsuleVideoLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(1) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isTerminalVideoLoadFailure(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)
}

private val SAFE_VIDEO_CACHE_KEY = Regex("capsule:video(?:-stream)?:(?:(?:audio|video):)?[A-Za-z0-9_-]{11}:[0-9]+")

/** Observe each real HTTP range, including after seeks, before it enters the cache. */
internal class CapsuleVideoLinkDataSource(private val upstream: DataSource) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        // Validate before DefaultDataSource can open a local file/content URI
        // supplied by an HLS child playlist or encryption-key reference.
        if (!com.nikhil.yt.innertube.video.VideoStreamValidation.isSupportedUrl(url))
            throw IOException("Untrusted VIDEO media address")
        val safeKey = dataSpec.key?.takeIf {
            it.length <= 80 && it.matches(SAFE_VIDEO_CACHE_KEY)
        } ?: "media"
        if (VideoRejectedLinks.isRejected(url)) throw VideoRetiredLinkException()
        val start = System.nanoTime()
        try {
            return upstream.open(dataSpec).also {
                if (com.nikhil.yt.utils.GlobalLog.isEnabled) {
                    Timber.tag("CapsuleVideo").i(
                        "video-cdn-open key=%s pos=%d len=%d ref=%s ms=%d",
                        safeKey, dataSpec.position, dataSpec.length,
                        AudioCdnLinkIdentity.ref(url), (System.nanoTime() - start) / 1_000_000L,
                    )
                }
            }
        } catch (failure: IOException) {
            val status = generateSequence(failure as Throwable?) { it.cause }.take(12)
                .filterIsInstance<InvalidResponseCodeException>().firstOrNull()?.responseCode
            if (status == 401 || status == 403 || status == 410) VideoRejectedLinks.reject(url)
            if (status == null && failure.isExpectedAudioCdnInterruption()) {
                if (com.nikhil.yt.utils.GlobalLog.isEnabled)
                    Timber.tag("CapsuleVideo").d("video-cdn-interrupted key=%s", safeKey)
                throw failure
            }
            Timber.tag("CapsuleVideo").w(
                "video-cdn-failed key=%s pos=%d len=%d ref=%s status=%s type=%s ms=%d",
                safeKey, dataSpec.position, dataSpec.length,
                AudioCdnLinkIdentity.ref(url), status?.toString() ?: "none",
                failure.javaClass.simpleName, (System.nanoTime() - start) / 1_000_000L,
            )
            throw failure
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun close() = upstream.close()
}
