/* Capsule MUSIC — maintained token-backed VIDEO transport. GPL-3.0. */
package com.nikhil.yt.playback.video

import com.metrolist.innertubex.extraction.ExtractedStream
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.video.CapsuleSabrVideoSpec
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import com.nikhil.yt.playback.audio.CapsuleInnerTubeXPlayer
import com.nikhil.yt.playback.audio.CapsulePlaybackSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.IOException
import java.util.Base64

/**
 * Reuse InnerTubeX's verified client, visitor, PO-token and n-transform contracts.
 * Only the resulting VIDEO session reaches the dedicated VIDEO Media3 source.
 * No token/account data crosses the legacy parser IPC boundary.
 */
internal object CapsuleWebVideoResolver {
    const val BACKEND = "innertubex-web-remix"
    private var session: Any? = null
    private var botRetryAtMs = 0L
    private val retryAtByClip = LinkedHashMap<String, Long>()

    // YouTubeVideoResolver's mutex serializes this state and all real attempts.
    suspend fun resolve(videoId: String, quality: CapsuleVideoQuality): CapsuleResolvedVideo? {
        val identity = CapsuleInnerTubeXPlayer.sessionIdentity()
        if (identity != session) {
            session = identity
            botRetryAtMs = 0L
            retryAtByClip.clear()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < botRetryAtMs || now < (retryAtByClip[videoId] ?: 0L)) return null
        val started = now
        val epoch = CapsuleVideoRequestGuard.resetEpoch()
        return try {
            val extraction = CapsuleWebVideoExtractor.resolve(videoId, quality.maxHeight ?: 720)
            val resolved = tokenBackedVideo(extraction.stream, quality.maxHeight ?: 720, extraction.index)
            retryAtByClip.remove(videoId)
            Timber.tag("CapsuleVideo").i(
                "VIDEO web-ready clip=%s elapsedMs=%d delivery=%s height=%d tokenPresent=%s",
                videoId, android.os.SystemClock.elapsedRealtime() - started,
                resolved.delivery, resolved.height, (resolved.sabrSpec?.poTokenBase64?.isNotEmpty() == true) ||
                    android.net.Uri.parse(resolved.videoUrl).getQueryParameter("pot")?.isNotBlank() == true,
            )
            resolved
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            retryAtByClip[videoId] = now + 5_000L
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (CapsuleVideoRequestGuard.isBlocked()) throw CapsuleVideoRequestGuard.RequestBlockedException("VIDEO cooldown active", failure)
            val kind = if ((failure as? YouTubeVideoResolver.VideoBackendException)?.failure == CapsuleVideoFailure.BOT_BLOCKED)
                CapsuleVideoRequestGuard.FailureKind.BOT_CHECK else CapsuleVideoRequestGuard.classify(failure)
            if (kind == CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED ||
                CapsulePlaybackSafety.isRateLimitedException(failure)) {
                CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("rate_limited", epoch)
                throw YouTubeVideoResolver.VideoBackendException(CapsuleVideoFailure.RATE_LIMITED, "VIDEO rate limited")
            }
            if (kind == CapsuleVideoRequestGuard.FailureKind.BOT_CHECK) botRetryAtMs = now + 60_000L
            retryAtByClip[videoId] = now + 30_000L
            while (retryAtByClip.size > 64) retryAtByClip.remove(retryAtByClip.keys.first())
            // Exception text may contain a signed URL: diagnostics remain structural.
            Timber.tag("CapsuleVideo").w("VIDEO web-fallback clip=%s kind=%s type=%s elapsedMs=%d",
                videoId, kind, failure.javaClass.simpleName, android.os.SystemClock.elapsedRealtime() - started)
            null
        }
    }
}

/** Match delivery to Media3: signed direct tracks or a validated MP4 UMP session.
 * The installed BotGuard provider supports WEB_REMIX direct. Web SABR profiles
 * advertise a different attestation contract; never mislabel that provider to
 * force a token onto an incompatible SABR client. */
internal fun tokenBackedVideo(stream: ExtractedStream, maxHeight: Int, index: CapsuleWebVideoIndex? = null): CapsuleResolvedVideo {
    require(stream.videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
    if (stream.sabrVideoBootstrap == null) {
        val videoUrl = requireNotNull(stream.videoUrl)
        val height = requireNotNull(stream.videoHeight)
        require(height in 1..maxHeight && stream.videoItag != null)
        require(CapsuleVideoIpc.isSafeStreamUrl(videoUrl) && CapsuleVideoIpc.isSafeStreamUrl(stream.audioUrl))
        val headers = stream.headers.filterKeys { key ->
            key.equals("User-Agent", true) || key.equals("Origin", true) || key.equals("Referer", true)
        }
        require(headers.values.all { it.length <= 2048 && '\r' !in it && '\n' !in it })
        val expires = requireNotNull(stream.expiresAt).toEpochMilliseconds()
        require(com.nikhil.yt.innertube.video.VideoStreamValidation.isFresh(expires))
        val source = CapsuleResolvedVideo(
            videoId = stream.videoId, videoUrl = videoUrl, audioUrl = stream.audioUrl,
            qualityLabel = "${height}p", width = stream.videoWidth ?: 0, height = height,
            videoItag = requireNotNull(stream.videoItag), audioItag = stream.itag,
            expiresAtMs = expires, backend = CapsuleWebVideoResolver.BACKEND,
            maxHeight = maxHeight, userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value,
            requestHeaders = headers,
        )
        return index?.indexed(source) ?: source
    }
    var bootstrap = requireNotNull(stream.sabrVideoBootstrap)
    // Capsule's dual-track SABR source consumes MP4. InnerTubeX can also select
    // WebM/VP9: choose a session-advertised H264 variant before pinning the itags.
    val mp4 = bootstrap.videoVariants.filter {
        it.height in 1..maxHeight && it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
    }.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
    if (mp4 != null) bootstrap = bootstrap.withVideoVariant(mp4)
    require(bootstrap.mimeType.startsWith("audio/mp4")) { "VIDEO SABR audio container unsupported" }
    require(bootstrap.selectedVideoMimeType.orEmpty().startsWith("video/mp4") &&
        bootstrap.selectedVideoMimeType.orEmpty().contains("avc1")) { "VIDEO SABR video container unsupported" }
    val video = requireNotNull(bootstrap.selectedVideoFormat)
    val height = requireNotNull(bootstrap.selectedVideoHeight)
    require(height in 1..maxHeight)
    val b64 = Base64.getEncoder()
    val spec = CapsuleSabrVideoSpec(
        ustreamerConfigBase64 = b64.encodeToString(bootstrap.videoPlaybackUstreamerConfig),
        videoItag = video.itag, videoLastModified = video.lastModified,
        videoLength = requireNotNull(bootstrap.selectedVideoContentLengthBytes),
        audioItag = bootstrap.audioFormat.itag, audioLastModified = bootstrap.audioFormat.lastModified,
        audioLength = requireNotNull(bootstrap.contentLengthBytes),
        clientName = bootstrap.clientName, clientVersion = bootstrap.clientVersion,
        durationMs = bootstrap.durationMs,
        poTokenBase64 = bootstrap.poToken?.let(b64::encodeToString).orEmpty(),
        videoXtags = video.xtags, audioXtags = bootstrap.audioFormat.xtags,
        requestOrigin = bootstrap.requestOrigin,
    )
    require(spec.isValid()) { "VIDEO SABR metadata invalid" }
    val url = bootstrap.serverAbrStreamingUrl
    require(CapsuleVideoIpc.isSafeStreamUrl(url)) { "VIDEO SABR endpoint invalid" }
    val urlExpiry = android.net.Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L)
    val expires = listOfNotNull(urlExpiry, stream.expiresAt?.toEpochMilliseconds()).minOrNull()
        ?: throw IOException("VIDEO SABR expiry missing")
    require(com.nikhil.yt.innertube.video.VideoStreamValidation.isFresh(expires))
    return CapsuleResolvedVideo(
        videoId = stream.videoId, videoUrl = url,
        qualityLabel = "${height}p SABR", width = bootstrap.selectedVideoWidth ?: 0, height = height,
        videoItag = video.itag, audioItag = bootstrap.audioFormat.itag,
        expiresAtMs = expires, backend = CapsuleWebVideoResolver.BACKEND,
        delivery = CapsuleVideoDelivery.SABR, maxHeight = maxHeight,
        userAgent = requireNotNull(bootstrap.requestUserAgent), sabrSpec = spec,
    )
}
