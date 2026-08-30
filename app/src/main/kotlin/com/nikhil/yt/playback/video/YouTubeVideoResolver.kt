/*
 * Capsule MUSIC
 * Dedicated YouTube Music video resolver.
 *
 * Normal Capsule audio playback is intentionally not changed here.
 * VIDEO resolves an official YouTube Music clip first, then chooses either
 * a stable muxed stream or a higher-quality adaptive video + audio pair.
 *
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.YouTubeMusicVideoLinkResolver
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_TESTSUITE
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_UNPLUGGED
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IPADOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS_MUSIC
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.MOBILE
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.VISIONOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.pages.NewPipeUtils
import com.nikhil.yt.utils.StreamClientUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object YouTubeVideoResolver {
    private const val TAG = "CapsuleVideo"
    private const val CACHE_SAFETY_MS = 30_000L

    data class ResolvedVideo(
        val sourceMediaId: String,
        val videoId: String,
        val videoStreamUrl: String,
        val videoFormat: PlayerResponse.StreamingData.Format,
        val audioStreamUrl: String? = null,
        val audioFormat: PlayerResponse.StreamingData.Format? = null,
        val expiresAtMs: Long,
    ) {
        val streamUrl: String
            get() = videoStreamUrl

        val format: PlayerResponse.StreamingData.Format
            get() = videoFormat

        val isAdaptive: Boolean
            get() = !audioStreamUrl.isNullOrBlank() && audioFormat != null

        val qualityLabel: String
            get() =
                videoFormat.qualityLabel
                    ?: videoFormat.height?.let { "${it}p" }
                    ?: videoFormat.quality.ifBlank { "VIDEO" }
    }

    private data class Cached(
        val sourceMediaId: String,
        val videoId: String,
        val videoStreamUrl: String,
        val videoFormat: PlayerResponse.StreamingData.Format,
        val audioStreamUrl: String?,
        val audioFormat: PlayerResponse.StreamingData.Format?,
        val expiresAtMs: Long,
    ) {
        fun asResolved(): ResolvedVideo =
            ResolvedVideo(
                sourceMediaId = sourceMediaId,
                videoId = videoId,
                videoStreamUrl = videoStreamUrl,
                videoFormat = videoFormat,
                audioStreamUrl = audioStreamUrl,
                audioFormat = audioFormat,
                expiresAtMs = expiresAtMs,
            )
    }

    private val cache = ConcurrentHashMap<String, Cached>()
    private val latestCacheKeyByVideoId = ConcurrentHashMap<String, String>()

    @Volatile
    private var clientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    /*
     * Keep VIDEO isolated from the audio client preference. WEB is first because
     * it usually exposes the widest set of ordinary VOD formats. The rest are
     * fallbacks only; the loop stops on the first validated result.
     */
    private val videoClients: List<YouTubeClient> =
        listOf(
            WEB,
            IOS,
            ANDROID_MUSIC,
            MOBILE,
            TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            ANDROID_VR_NO_AUTH,
            IOS_MUSIC,
            TVHTML5,
            WEB_REMIX,
            ANDROID_VR_1_61_48,
            ANDROID_VR_1_43_32,
            ANDROID_CREATOR,
            ANDROID_TESTSUITE,
            ANDROID_UNPLUGGED,
            IPADOS,
            VISIONOS,
            WEB_CREATOR,
        ).distinct()

    fun invalidate(videoId: String) {
        val prefix = "${videoId.trim()}:"
        cache.keys.removeIf { it.startsWith(prefix) }
        latestCacheKeyByVideoId.remove(videoId.trim())
    }

    /**
     * Synchronous cache lookup used by MusicService's MediaSource.Factory.
     * resolveForSong() always warms this cache before the queue item is replaced.
     */
    fun peekResolved(videoId: String): ResolvedVideo? {
        val id = videoId.trim()
        val key = latestCacheKeyByVideoId[id] ?: return null
        val now = System.currentTimeMillis()
        val cached = cache[key]
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?: return null
        return cached.asResolved()
    }

    suspend fun resolveForSong(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
        quality: CapsuleVideoQuality,
    ): Result<ResolvedVideo> = runCatching {
        val canonicalId = sourceMediaId.trim()
        require(canonicalId.isNotBlank()) { "Missing YouTube Music track id" }

        val link =
            YouTubeMusicVideoLinkResolver
                .resolve(
                    sourceMediaId = canonicalId,
                    title = title,
                    artists = artists,
                    durationSeconds = durationSeconds,
                )
                .getOrThrow()

        val cached =
            resolveLinkedVideo(
                sourceMediaId = canonicalId,
                videoId = link.videoId,
                quality = quality,
                adaptiveAllowed = true,
                expectedTitle = title,
                expectedArtists = artists,
                expectedDurationSeconds = durationSeconds,
            ).getOrThrow()

        latestCacheKeyByVideoId[link.videoId] = preferredCacheKey(link.videoId, quality)
        cached.asResolved()
    }

    /**
     * Safe single-stream fallback for the old capsule-video ResolvingDataSource.
     * Higher-quality adaptive playback is created by MusicService's MediaSource
     * factory from [peekResolved].
     */
    suspend fun resolveMuxed(
        videoId: String,
        quality: CapsuleVideoQuality,
    ): Result<ResolvedVideo> = runCatching {
        val id = videoId.trim()
        require(id.isNotBlank()) { "Missing linked YouTube video id" }

        resolveLinkedVideo(
            sourceMediaId = id,
            videoId = id,
            quality = quality,
            adaptiveAllowed = false,
            expectedTitle = null,
            expectedArtists = emptyList(),
            expectedDurationSeconds = null,
        ).getOrThrow().asResolved()
    }

    private suspend fun resolveLinkedVideo(
        sourceMediaId: String,
        videoId: String,
        quality: CapsuleVideoQuality,
        adaptiveAllowed: Boolean,
        expectedTitle: String?,
        expectedArtists: List<String>,
        expectedDurationSeconds: Int?,
    ): Result<Cached> = runCatching {
        val key =
            if (adaptiveAllowed) {
                preferredCacheKey(videoId, quality)
            } else {
                muxedCacheKey(videoId, quality)
            }

        val now = System.currentTimeMillis()
        cache[key]
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?.let { return@runCatching it }

        val signatureTimestamp =
            NewPipeUtils.getSignatureTimestamp(videoId)
                .getOrNull()

        val isLoggedIn = YouTube.cookie != null
        var lastError: Throwable? = null

        for (client in videoClients) {
            if (client.loginRequired && !isLoggedIn) continue

            val response =
                runCatching {
                    YouTube.player(
                        videoId = videoId,
                        playlistId = null,
                        client = client,
                        signatureTimestamp = signatureTimestamp,
                    ).getOrThrow()
                }.onFailure {
                    lastError = it
                    Timber.tag(TAG).d(
                        it,
                        "Video player response failed for ${client.clientName}",
                    )
                }.getOrNull() ?: continue

            if (response.playabilityStatus.status != "OK") {
                lastError =
                    IllegalStateException(
                        response.playabilityStatus.reason
                            ?: "YouTube video is not playable",
                    )
                continue
            }

            val playerMusicVideoType = response.videoDetails?.musicVideoType
            if (
                playerMusicVideoType != null &&
                playerMusicVideoType != MUSIC_VIDEO_TYPE_OMV
            ) {
                lastError =
                    IllegalStateException(
                        "Matched item is not an official YouTube Music video",
                    )
                continue
            }

            if (
                expectedTitle != null &&
                !matchesExpectedVideoDetails(
                    details = response.videoDetails,
                    expectedTitle = expectedTitle,
                    expectedArtists = expectedArtists,
                    expectedDurationSeconds = expectedDurationSeconds,
                )
            ) {
                lastError =
                    IllegalStateException(
                        "YouTube player metadata did not match the current song",
                    )
                continue
            }

            val preferMuxedFirst = quality == CapsuleVideoQuality.P360

            if (preferMuxedFirst) {
                tryMuxed(
                    sourceMediaId = sourceMediaId,
                    videoId = videoId,
                    response = response,
                    client = client,
                    quality = quality,
                )?.let { cached ->
                    cache[key] = cached
                    if (adaptiveAllowed) latestCacheKeyByVideoId[videoId] = key
                    return@runCatching cached
                }
            }

            if (adaptiveAllowed) {
                tryAdaptive(
                    sourceMediaId = sourceMediaId,
                    videoId = videoId,
                    response = response,
                    client = client,
                    quality = quality,
                )?.let { cached ->
                    cache[key] = cached
                    latestCacheKeyByVideoId[videoId] = key
                    Timber.tag(TAG).i(
                        "Resolved $videoId as adaptive ${cached.videoFormat.height ?: 0}p " +
                            "via ${client.clientName}",
                    )
                    return@runCatching cached
                }
            }

            tryMuxed(
                sourceMediaId = sourceMediaId,
                videoId = videoId,
                response = response,
                client = client,
                quality = quality,
            )?.let { cached ->
                cache[key] = cached
                if (adaptiveAllowed) latestCacheKeyByVideoId[videoId] = key
                Timber.tag(TAG).i(
                    "Resolved $videoId as muxed ${cached.videoFormat.height ?: 0}p " +
                        "via ${client.clientName}",
                )
                return@runCatching cached
            }
        }

        throw lastError
            ?: IllegalStateException(
                "Could not resolve a compatible official YouTube Music video stream",
            )
    }

    private suspend fun tryAdaptive(
        sourceMediaId: String,
        videoId: String,
        response: PlayerResponse,
        client: YouTubeClient,
        quality: CapsuleVideoQuality,
    ): Cached? {
        val videoFormats = selectAdaptiveVideoFormats(response, quality)
        val audioFormats = selectAdaptiveAudioFormats(response)
        if (videoFormats.isEmpty() || audioFormats.isEmpty()) return null

        var audioChoice: Pair<PlayerResponse.StreamingData.Format, String>? = null
        for (audioFormat in audioFormats.take(4)) {
            val url = resolveAndValidate(audioFormat, videoId, client) ?: continue
            audioChoice = audioFormat to url
            break
        }
        val (audioFormat, audioUrl) = audioChoice ?: return null

        for (videoFormat in videoFormats.take(6)) {
            val videoUrl = resolveAndValidate(videoFormat, videoId, client) ?: continue
            val expiresAtMs = expiry(response)
            return Cached(
                sourceMediaId = sourceMediaId,
                videoId = videoId,
                videoStreamUrl = videoUrl,
                videoFormat = videoFormat,
                audioStreamUrl = audioUrl,
                audioFormat = audioFormat,
                expiresAtMs = expiresAtMs,
            )
        }

        return null
    }

    private suspend fun tryMuxed(
        sourceMediaId: String,
        videoId: String,
        response: PlayerResponse,
        client: YouTubeClient,
        quality: CapsuleVideoQuality,
    ): Cached? {
        for (format in selectMuxedFormats(response, quality).take(8)) {
            val url = resolveAndValidate(format, videoId, client) ?: continue
            return Cached(
                sourceMediaId = sourceMediaId,
                videoId = videoId,
                videoStreamUrl = url,
                videoFormat = format,
                audioStreamUrl = null,
                audioFormat = null,
                expiresAtMs = expiry(response),
            )
        }
        return null
    }

    private fun selectAdaptiveVideoFormats(
        response: PlayerResponse,
        quality: CapsuleVideoQuality,
    ): List<PlayerResponse.StreamingData.Format> {
        val maxHeight = quality.maxHeight ?: 720

        return response.streamingData
            ?.adaptiveFormats
            .orEmpty()
            .asSequence()
            .filter { format ->
                val height = format.height ?: 0
                val hasSource =
                    format.url != null ||
                        format.signatureCipher != null ||
                        format.cipher != null

                !format.isAudio &&
                    format.width != null &&
                    height in 1..maxHeight &&
                    format.bitrate > 0 &&
                    hasSource
            }
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> {
                    it.height ?: 0
                }.thenByDescending {
                    it.fps ?: 0
                }.thenByDescending {
                    it.mimeType.startsWith("video/mp4", ignoreCase = true)
                }.thenByDescending {
                    it.bitrate
                },
            )
            .toList()
    }

    private fun selectAdaptiveAudioFormats(
        response: PlayerResponse,
    ): List<PlayerResponse.StreamingData.Format> =
        response.streamingData
            ?.adaptiveFormats
            .orEmpty()
            .asSequence()
            .filter { format ->
                format.isAudio &&
                    format.bitrate > 0 &&
                    (
                        format.url != null ||
                            format.signatureCipher != null ||
                            format.cipher != null
                    )
            }
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> {
                    it.mimeType.startsWith("audio/mp4", ignoreCase = true)
                }.thenByDescending {
                    it.bitrate
                }.thenByDescending {
                    it.audioSampleRate ?: 0
                },
            )
            .toList()

    private fun selectMuxedFormats(
        response: PlayerResponse,
        quality: CapsuleVideoQuality,
    ): List<PlayerResponse.StreamingData.Format> {
        val maxHeight = quality.maxHeight ?: 720

        return response.streamingData
            ?.formats
            .orEmpty()
            .asSequence()
            .filter { format ->
                val height = format.height ?: 0
                val hasVideo = format.width != null && height > 0
                val hasAudio =
                    (format.audioChannels ?: 0) > 0 ||
                        format.audioQuality != null ||
                        format.mimeType.contains("mp4a", ignoreCase = true) ||
                        format.mimeType.contains("opus", ignoreCase = true)
                val hasSource =
                    format.url != null ||
                        format.signatureCipher != null ||
                        format.cipher != null

                hasVideo &&
                    hasAudio &&
                    height <= maxHeight &&
                    format.bitrate > 0 &&
                    hasSource
            }
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> {
                    it.height ?: 0
                }.thenByDescending {
                    it.mimeType.startsWith("video/mp4", ignoreCase = true)
                }.thenByDescending {
                    it.url != null
                }.thenByDescending {
                    it.bitrate
                },
            )
            .toList()
    }

    private suspend fun resolveAndValidate(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
    ): String? {
        var url =
            NewPipeUtils.getStreamUrl(
                format = format,
                videoId = videoId,
                client = client,
            ).getOrNull() ?: return null

        url =
            StreamClientUtils.patchClientVersion(
                url,
                client.clientVersion,
            )

        return url.takeIf { validate(it, client.userAgent) }
    }

    private fun expiry(response: PlayerResponse): Long {
        val expiresSeconds = response.streamingData?.expiresInSeconds ?: 300
        return System.currentTimeMillis() + expiresSeconds.coerceAtLeast(60) * 1000L
    }

    private fun matchesExpectedVideoDetails(
        details: PlayerResponse.VideoDetails?,
        expectedTitle: String,
        expectedArtists: List<String>,
        expectedDurationSeconds: Int?,
    ): Boolean {
        details ?: return false

        val expectedTitleNorm = normalizeTitleForCheck(expectedTitle)
        val actualTitleNorm = normalizeTitleForCheck(details.title)
        val artistNorms =
            expectedArtists
                .map(::normalizeForCheck)
                .filter { it.isNotBlank() }

        val titleMatches =
            actualTitleNorm == expectedTitleNorm ||
                artistNorms.any { artist ->
                    actualTitleNorm == "$artist $expectedTitleNorm" ||
                        actualTitleNorm == "$expectedTitleNorm $artist"
                }
        if (!titleMatches) return false

        if (artistNorms.isNotEmpty()) {
            val author =
                normalizeForCheck(details.author)
                    .removeSuffix(" vevo")
                    .removeSuffix("vevo")
                    .trim()
            val artistMatches =
                artistNorms.any { artist ->
                    author.contains(artist) || artist.contains(author)
                }
            if (!artistMatches) return false
        }

        val expectedDuration = expectedDurationSeconds?.takeIf { it > 0 }
        val actualDuration = details.lengthSeconds.toIntOrNull()?.takeIf { it > 0 }
        if (expectedDuration != null && actualDuration != null) {
            if (kotlin.math.abs(expectedDuration - actualDuration) > 65) return false
        }

        return true
    }

    private fun normalizeTitleForCheck(value: String): String =
        normalizeForCheck(value)
            .replace(Regex("\\bofficial\\s+music\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\s+video\\b"), " ")
            .replace(Regex("\\bmusic\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeForCheck(value: String): String =
        value
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun preferredCacheKey(
        videoId: String,
        quality: CapsuleVideoQuality,
    ) = "$videoId:${quality.name}:preferred"

    private fun muxedCacheKey(
        videoId: String,
        quality: CapsuleVideoQuality,
    ) = "$videoId:${quality.name}:muxed"

    private fun validate(
        url: String,
        fallbackUserAgent: String,
    ): Boolean =
        runCatching {
            val clientParam =
                url.toHttpUrlOrNull()
                    ?.queryParameter("c")
                    ?.trim()
                    .orEmpty()

            val userAgent =
                StreamClientUtils.resolveUserAgent(clientParam)
                    .ifEmpty { fallbackUserAgent }

            val originReferer =
                StreamClientUtils.resolveOriginReferer(clientParam)

            val request =
                Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=0-1023")
                    .apply {
                        originReferer.origin?.let { header("Origin", it) }
                        originReferer.referer?.let { header("Referer", it) }
                    }
                    .build()

            currentClient()
                .newCall(request)
                .execute()
                .use { response ->
                    response.code in 200..399 || response.code == 416
                }
        }.getOrDefault(false)

    private fun currentClient(): OkHttpClient {
        val proxy = YouTube.streamProxy

        clientPair?.let { (cachedProxy, cachedClient) ->
            if (cachedProxy == proxy) return cachedClient
        }

        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
            .also { clientPair = proxy to it }
    }
}
