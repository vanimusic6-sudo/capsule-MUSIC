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
    val backend: String = "newpipe",
    val delivery: CapsuleVideoDelivery = CapsuleVideoDelivery.PROGRESSIVE,
    val maxHeight: Int = 720,
    val dashManifest: String? = null,
    val userAgent: String? = null,
)

enum class CapsuleVideoDelivery { PROGRESSIVE, HLS, DASH }

enum class CapsuleNewPipeFailure {
    UNAVAILABLE, NETWORK, EXTRACTOR, RATE_LIMITED, BOT_BLOCKED, UNKNOWN
}

enum class CapsuleNewPipeQuality(val maxHeight: Int) {
    AUTO(720), P360(360), P480(480), P720(720)
}

object CapsuleNewPipeExtractor {
    private val routeHealth = CapsuleVideoRouteHealth()
    @Volatile private var lastWorkingBackend: String? = null
    private const val CACHE_SAFETY_MS = 45_000L
    private const val DEFAULT_URL_TTL_MS = 3_600_000L
    private val initialized = AtomicBoolean(false)
    private const val INVENTORY_TTL_MS = 60_000L
    private data class Inventory(
        val videoStreams: List<VideoStream>, val videoOnlyStreams: List<VideoStream>,
        val audioStreams: List<AudioStream>, val hlsUrl: String,
        val durationMs: Long,
    )
    private data class CachedInfo(val info: Inventory, val untilMs: Long)
    // At most two VIDEO items. Never shared with the MUSIC/AUDIO extractor.
    private val recentMetadata = LinkedHashMap<String, CachedInfo>(4, 0.75f, true)

    @Synchronized
    fun initializeIfNeeded() {
        if (initialized.get()) return
        // Commit initialized only AFTER NewPipe really started. The previous
        // compareAndSet-first path could poison this process on failure.
        NewPipe.init(CapsuleNewPipeDownloader())
        initialized.set(true)
    }

    @Synchronized
    fun invalidate(videoId: String) {
        recentMetadata.remove(videoId)
    }

    @Synchronized
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

    /** Independent direct routes, then the maintained parser/cipher fallback. */
    fun resolveWithFallback(
        videoId: String, quality: CapsuleNewPipeQuality,
        muxedOnly: Boolean = false, failedBackend: String? = null,
        onAttempt: (String, Long, Boolean) -> Unit = { _, _, _ -> },
        onRouteFailure: (String, Throwable) -> Unit = { _, _ -> },
    ): CapsuleNewPipeResolved = resolveVideoRoutes(
        failedBackend, health = routeHealth, preferredBackend = lastWorkingBackend,
        onAttempt = { route, elapsed, success ->
            if (success) lastWorkingBackend = route.wireName
            onAttempt(route.wireName, elapsed, success)
        },
        onFailure = { route, failure -> onRouteFailure(route.wireName, failure) }) { route ->
        when (route) {
            CapsuleVideoRoute.ANDROID_VR -> CapsuleDirectVideoExtractor.resolveVr(videoId, quality, muxedOnly)
            CapsuleVideoRoute.VISIONOS -> CapsuleDirectVideoExtractor.resolve(videoId, quality, muxedOnly)
            CapsuleVideoRoute.PIPEPIPE -> CapsulePipePipeExtractor.resolve(videoId, quality, muxedOnly)
            CapsuleVideoRoute.ANDROID_REEL -> CapsuleDirectVideoExtractor.resolveReel(videoId, quality)
            CapsuleVideoRoute.NEWPIPE -> resolve(videoId, quality, muxedOnly)
        }
    }

    fun classify(throwable: Throwable): CapsuleNewPipeFailure {
        if (throwable is CapsuleVideoRoutesException) return throwable.failure
        if (throwable is CapsuleDirectVideoException) return throwable.failure
        val text =
            generateSequence(throwable) { it.cause }
                .take(8)
                .mapNotNull { it.message }
                .joinToString(" ")

        /*
         * CapsuleNewPipeDownloader represents HTTP 429 as ReCaptchaException
         * for NewPipe API compatibility, so machine-like 429 text must win
         * before the exception class itself is treated as a bot/captcha event.
         */
        // NewPipe's explicit LOGIN_REQUIRED anti-bot verdict is NOT a normal
        // missing/age-restricted clip. Treat it as VIDEO-only bot cooldown;
        // ordinary AUDIO remains unaffected.
        if (text.contains("confirm that you're not a bot", ignoreCase = true) ||
            text.contains("confirm you are not a bot", ignoreCase = true)
        ) return CapsuleNewPipeFailure.BOT_BLOCKED

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
                    throwable is ReCaptchaException || throwable is dev.maxrave.pipepipe.extractor.exceptions.ReCaptchaException -> CapsuleNewPipeFailure.BOT_BLOCKED
                    throwable is ContentNotAvailableException || throwable is dev.maxrave.pipepipe.extractor.exceptions.ContentNotAvailableException -> CapsuleNewPipeFailure.UNAVAILABLE
                    throwable is SocketTimeoutException || throwable is IOException ->
                        CapsuleNewPipeFailure.NETWORK
                    throwable is ExtractionException || throwable is dev.maxrave.pipepipe.extractor.exceptions.ExtractionException -> CapsuleNewPipeFailure.EXTRACTOR
                    else -> CapsuleNewPipeFailure.UNKNOWN
                }
        }
    }

    fun routeDiagnostic(throwable: Throwable): String =
        (throwable as? CapsuleDirectVideoException)?.diagnostic ?: throwable.javaClass.simpleName

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
        require(expiresAt > System.currentTimeMillis() + CACHE_SAFETY_MS) {
            "NewPipe returned an expired VIDEO signed URL"
        }
        return CapsuleNewPipeResolved(
            videoId = videoId,
            videoUrl = content,
            audioUrl = audio?.content,
            qualityLabel = getResolution().takeIf { it.isNotBlank() } ?: "${height}p",
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
        // Do not pretend an expired signed URL is still valid.
        return parsed ?: now + DEFAULT_URL_TTL_MS
    }
}
