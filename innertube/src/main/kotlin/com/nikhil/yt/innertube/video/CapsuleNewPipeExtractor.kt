/*
 * Capsule MUSIC
 * NewPipeExtractor adapter for the isolated VIDEO process.
 * GPL-3.0
 */
package com.nikhil.yt.innertube.video

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class CapsuleNewPipeResolved(
    val videoId: String,
    val videoUrl: String,
    val audioUrl: String?,
    val qualityLabel: String,
    val width: Int,
    val height: Int,
    val videoItag: Int,
    val audioItag: Int,
    val expiresAtMs: Long,
)

enum class CapsuleNewPipeFailure { UNAVAILABLE, NETWORK, EXTRACTOR, RATE_LIMITED, BOT_BLOCKED, UNKNOWN }
enum class CapsuleNewPipeQuality(val maxHeight: Int) { AUTO(720), P360(360), P480(480), P720(720) }

object CapsuleNewPipeExtractor {
    private const val CACHE_SAFETY_MS = 45_000L
    private const val DEFAULT_URL_TTL_MS = 3_600_000L
    private val initialized = AtomicBoolean(false)

    fun initializeIfNeeded() {
        if (initialized.compareAndSet(false, true)) NewPipe.init(CapsuleNewPipeDownloader())
    }

    fun resolve(videoId: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean = false): CapsuleNewPipeResolved {
        initializeIfNeeded()
        val id = videoId.trim()
        require(id.isNotBlank()) { "Missing YouTube video id" }
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")

        val muxed = info.videoStreams.asSequence().filter(::isProgressiveUrl).filter { it.height > 0 }.toList()
        val adaptiveVideo = info.videoOnlyStreams.asSequence().filter(::isProgressiveUrl).filter { it.height > 0 }.toList()
        val audio = info.audioStreams.asSequence().filter(::isProgressiveUrl)
            .maxWithOrNull(compareBy<AudioStream> { it.averageBitrate }.thenBy { it.bitrate })
        val selectedMuxed = selectMuxed(muxed, quality)

        if (muxedOnly) {
            return selectedMuxed?.toResolved(id, null)
                ?: throw ContentNotAvailableException("NewPipe returned no muxed VIDEO stream")
        }

        if (quality == CapsuleNewPipeQuality.AUTO || quality == CapsuleNewPipeQuality.P360) {
            selectedMuxed?.let { return it.toResolved(id, null) }
            selectAdaptive(adaptiveVideo, quality)?.let { video ->
                return video.toResolved(id, requireNotNull(audio) { "NewPipe returned video-only stream without audio" })
            }
        } else {
            selectAdaptive(adaptiveVideo, quality)?.let { video -> if (audio != null) return video.toResolved(id, audio) }
            selectedMuxed?.let { return it.toResolved(id, null) }
        }
        throw ContentNotAvailableException("NewPipe returned no compatible VIDEO stream")
    }

    fun classify(throwable: Throwable): CapsuleNewPipeFailure {
        val text = generateSequence(throwable as Throwable?) { it?.cause }.take(8).mapNotNull { it?.message }.joinToString(" ").lowercase()
        return when {
            throwable is ReCaptchaException || "captcha" in text || "not a bot" in text || "confirm you're not a bot" in text || "confirm you’re not a bot" in text || "unusual traffic" in text -> CapsuleNewPipeFailure.BOT_BLOCKED
            "429" in text || "too many requests" in text || "rate limit" in text -> CapsuleNewPipeFailure.RATE_LIMITED
            throwable is ContentNotAvailableException || "video unavailable" in text || "private video" in text || "not available in your country" in text || "age-restricted" in text -> CapsuleNewPipeFailure.UNAVAILABLE
            throwable is SocketTimeoutException || throwable is IOException || "timeout" in text || "unable to resolve host" in text || "connection reset" in text -> CapsuleNewPipeFailure.NETWORK
            throwable is ExtractionException -> CapsuleNewPipeFailure.EXTRACTOR
            else -> CapsuleNewPipeFailure.UNKNOWN
        }
    }

    private fun isProgressiveUrl(stream: org.schabi.newpipe.extractor.stream.Stream): Boolean =
        stream.isUrl && stream.content.startsWith("http", ignoreCase = true) && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun selectMuxed(streams: List<VideoStream>, quality: CapsuleNewPipeQuality): VideoStream? {
        if (streams.isEmpty()) return null
        val maxHeight = quality.maxHeight
        return streams.filter { it.height <= maxHeight }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate })
            ?: streams.minByOrNull { it.height }
    }

    private fun selectAdaptive(streams: List<VideoStream>, quality: CapsuleNewPipeQuality): VideoStream? {
        if (streams.isEmpty()) return null
        val target = quality.maxHeight
        return streams.filter { it.height <= target }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate })
            ?: streams.minByOrNull { it.height }
    }

    private fun VideoStream.toResolved(videoId: String, audio: AudioStream?): CapsuleNewPipeResolved {
        val expiresAt = listOfNotNull(extractExpiryMs(content), audio?.content?.let(::extractExpiryMs)).minOrNull()
            ?: (System.currentTimeMillis() + DEFAULT_URL_TTL_MS)
        return CapsuleNewPipeResolved(
            videoId = videoId,
            videoUrl = content,
            audioUrl = audio?.content,
            qualityLabel = resolution.takeIf { it.isNotBlank() } ?: "${height}p",
            width = width,
            height = height,
            videoItag = itag,
            audioItag = audio?.itag ?: -1,
            expiresAtMs = expiresAt,
        )
    }

    private fun extractExpiryMs(url: String): Long {
        val now = System.currentTimeMillis()
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val expireSeconds = query.split('&').firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()
        val parsed = expireSeconds?.times(1000L)
        return (parsed ?: now + DEFAULT_URL_TTL_MS).coerceAtLeast(now + CACHE_SAFETY_MS)
    }
}
