/*
 * Capsule MUSIC
 * NewPipeExtractor adapter for the isolated VIDEO process.
 *
 * Capsule decides WHICH official music video to play. This adapter only turns
 * an exact video id into stream URLs. No custom YouTube player/cipher/client
 * rotation lives here; upstream NewPipeExtractor owns that maintenance.
 * GPL-3.0
 */
package com.nikhil.yt.innertube.video

import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
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

enum class CapsuleNewPipeFailure {
    UNAVAILABLE, NETWORK, EXTRACTOR, RATE_LIMITED, BOT_BLOCKED, UNKNOWN
}

enum class CapsuleNewPipeQuality(val maxHeight: Int) {
    AUTO(720), P360(360), P480(480), P720(720)
}

object CapsuleNewPipeExtractor {
    private const val CACHE_SAFETY_MS = 45_000L
    private const val DEFAULT_URL_TTL_MS = 3_600_000L
    private val initialized = AtomicBoolean(false)

    fun initializeIfNeeded() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(CapsuleNewPipeDownloader())
        }
    }

    fun resolve(
        videoId: String,
        quality: CapsuleNewPipeQuality,
        muxedOnly: Boolean = false,
    ): CapsuleNewPipeResolved {
        initializeIfNeeded()

        val id = videoId.trim()
        require(id.isNotBlank()) { "Missing YouTube video id" }

        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")

        val muxed =
            info.videoStreams
                .asSequence()
                .filter(::isProgressiveUrl)
                .filter { it.height > 0 }
                .toList()

        val adaptiveVideo =
            info.videoOnlyStreams
                .asSequence()
                .filter(::isProgressiveUrl)
                .filter { it.height > 0 }
                .toList()

        val audio =
            info.audioStreams
                .asSequence()
                .filter(::isProgressiveUrl)
                .maxWithOrNull(
                    compareBy<AudioStream> { it.averageBitrate }
                        .thenBy { it.bitrate },
                )

        val selectedMuxed = selectMuxed(muxed, quality)

        if (muxedOnly) {
            return selectedMuxed?.toResolved(id, audio = null)
                ?: throw ContentNotAvailableException("NewPipe returned no muxed VIDEO stream")
        }

        /*
         * AUTO is intentionally muxed-first. One URL means one timeline and one
         * MediaSource, which is significantly more tolerant of seek/resume and
         * stale-stream recovery than merging separate progressive tracks.
         */
        if (quality == CapsuleNewPipeQuality.AUTO || quality == CapsuleNewPipeQuality.P360) {
            selectedMuxed?.let { return it.toResolved(id, audio = null) }
            selectAdaptive(adaptiveVideo, quality)?.let { video ->
                requireNotNull(audio) { "NewPipe returned video-only stream without audio" }
                return video.toResolved(id, audio)
            }
        } else {
            selectAdaptive(adaptiveVideo, quality)?.let { video ->
                if (audio != null) return video.toResolved(id, audio)
            }
            selectedMuxed?.let { return it.toResolved(id, audio = null) }
        }

        throw ContentNotAvailableException("NewPipe returned no compatible VIDEO stream")
    }

    fun classify(throwable: Throwable): CapsuleNewPipeFailure {
        val text =
            generateSequence(throwable as Throwable?) { it?.cause }
                .take(8)
                .mapNotNull { it?.message }
                .joinToString(" ")

        /*
         * CapsuleNewPipeDownloader represents HTTP 429 as ReCaptchaException
         * for NewPipe API compatibility, so machine-like 429 text must win
         * before the exception class itself is treated as a bot/captcha event.
         */
        return when (
            YouTubeFailureClassifier.classify(
                httpStatusCode = null,
                playabilityStatus = null,
                text = text,
            )
        ) {
            YouTubeFailureKind.RATE_LIMITED -> CapsuleNewPipeFailure.RATE_LIMITED
            YouTubeFailureKind.BOT_CHECK -> CapsuleNewPipeFailure.BOT_BLOCKED
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.PERMANENT,
            -> CapsuleNewPipeFailure.UNAVAILABLE

            YouTubeFailureKind.TRANSIENT -> CapsuleNewPipeFailure.NETWORK
            YouTubeFailureKind.FORBIDDEN -> CapsuleNewPipeFailure.EXTRACTOR
            YouTubeFailureKind.NONE ->
                when {
                    throwable is ReCaptchaException -> CapsuleNewPipeFailure.BOT_BLOCKED
                    throwable is ContentNotAvailableException -> CapsuleNewPipeFailure.UNAVAILABLE
                    throwable is SocketTimeoutException || throwable is IOException ->
                        CapsuleNewPipeFailure.NETWORK
                    throwable is ExtractionException -> CapsuleNewPipeFailure.EXTRACTOR
                    else -> CapsuleNewPipeFailure.UNKNOWN
                }
        }
    }

    private fun isProgressiveUrl(stream: org.schabi.newpipe.extractor.stream.Stream): Boolean =
        stream.isUrl &&
            stream.content.startsWith("http", ignoreCase = true) &&
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun selectMuxed(
        streams: List<VideoStream>,
        quality: CapsuleNewPipeQuality,
    ): VideoStream? {
        if (streams.isEmpty()) return null
        val maxHeight =
            when (quality) {
                CapsuleNewPipeQuality.AUTO -> 720
                CapsuleNewPipeQuality.P360 -> 360
                CapsuleNewPipeQuality.P480 -> 480
                CapsuleNewPipeQuality.P720 -> 720
            }

        return streams
            .filter { it.height <= maxHeight }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate })
            ?: streams.minByOrNull { it.height }
    }

    private fun selectAdaptive(
        streams: List<VideoStream>,
        quality: CapsuleNewPipeQuality,
    ): VideoStream? {
        if (streams.isEmpty()) return null
        val target =
            when (quality) {
                CapsuleNewPipeQuality.AUTO -> 720
                CapsuleNewPipeQuality.P360 -> 360
                CapsuleNewPipeQuality.P480 -> 480
                CapsuleNewPipeQuality.P720 -> 720
            }

        return streams
            .filter { it.height <= target }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate })
            ?: streams.minByOrNull { it.height }
    }

    private fun VideoStream.toResolved(
        videoId: String,
        audio: AudioStream?,
    ): CapsuleNewPipeResolved {
        val expiresAt =
            listOfNotNull(
                extractExpiryMs(content),
                audio?.content?.let(::extractExpiryMs),
            ).minOrNull() ?: (System.currentTimeMillis() + DEFAULT_URL_TTL_MS)
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
        val expireSeconds =
            query.split('&')
                .firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')
                ?.toLongOrNull()

        val parsed = expireSeconds?.times(1000L)
        return (parsed ?: now + DEFAULT_URL_TTL_MS)
            .coerceAtLeast(now + CACHE_SAFETY_MS)
    }
}
