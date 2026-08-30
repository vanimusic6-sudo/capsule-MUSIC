/*
 * Capsule MUSIC
 * Dedicated YouTube Music video resolver.
 *
 * Normal Capsule audio playback is intentionally not changed here.
 * VIDEO resolves an official YouTube Music clip first, then chooses either
 * a stable muxed stream or a higher-quality adaptive video + audio pair.
 *
 * v2 changes:
 *  - At most MAX_CLIENT_ATTEMPTS clients are tried per resolve, and the client
 *    that last worked is tried first. v1 walked six clients for the same video
 *    id within seconds, which is the single most scraper-looking pattern in the
 *    old code.
 *  - Stream probes now go through the request guard and share a per-resolve
 *    budget. v1 could fire ~70 ungated requests for one VIDEO switch.
 *  - The duplicated muxed pass at P360 is gone.
 *  - playabilityStatus is classified: a permanently unavailable video aborts
 *    the whole resolve instead of being retried on every client.
 *  - Blocking OkHttp work moved off the calling dispatcher.
 *
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.YouTubeMusicVideoLinkResolver
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.MOBILE
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.pages.NewPipeUtils
import com.nikhil.yt.utils.StreamClientUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object YouTubeVideoResolver {
    private const val TAG = "CapsuleVideo"
    private const val CACHE_SAFETY_MS = 30_000L

    /**
     * How many player clients a single resolve may try. Walking the full list
     * turns one user action into a burst of near-identical player requests
     * differing only by client name, which is exactly the shape anti-abuse
     * systems look for.
     */
    private const val MAX_CLIENT_ATTEMPTS = 3

    /**
     * How many googlevideo range probes a single resolve may spend. Once the
     * budget is gone we hand the URL to ExoPlayer unverified and let normal
     * playback error handling deal with it, which costs one request instead of
     * several.
     */
    private const val MAX_PROBES_PER_RESOLVE = 4

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

    /** Per-resolve allowance for stream probes. Not thread safe by design. */
    private class ProbeBudget(private var remaining: Int) {
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining -= 1
            return true
        }
    }

    private val cache = ConcurrentHashMap<String, Cached>()
    private val latestCacheKeyByVideoId = ConcurrentHashMap<String, String>()

    @Volatile
    private var clientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    /**
     * The client that produced the last playable stream. Reusing it keeps a
     * session consistent instead of cycling identities per track.
     */
    @Volatile
    private var preferredClient: YouTubeClient? = null

    /*
     * Safety-first VIDEO clients, in fallback order.
     *
     * The list stays broad for compatibility, but MAX_CLIENT_ATTEMPTS means a
     * single resolve never walks all of it.
     *
     * A 403/429/bot response on the API surface stops the VIDEO path
     * immediately; AUDIO is separate.
     */
    private val videoClients: List<YouTubeClient> =
        listOf(
            WEB,
            IOS,
            ANDROID_MUSIC,
            MOBILE,
            TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            WEB_REMIX,
        ).distinct()

    private fun orderedClients(): List<YouTubeClient> {
        val preferred = preferredClient ?: return videoClients

        return buildList {
            add(preferred)
            addAll(videoClients.filterNot { it.clientName == preferred.clientName })
        }
    }

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
        CapsuleVideoRequestGuard.noteSuccess()
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

        if (CapsuleVideoRequestGuard.isBlocked()) {
            throw CapsuleVideoRequestGuard.RequestBlockedException(
                "YouTube VIDEO paused for " +
                    "${CapsuleVideoRequestGuard.remainingBackoffMs() / 1000L}s",
            )
        }

        val signatureTimestamp =
            NewPipeUtils.getSignatureTimestamp(videoId)
                .getOrNull()

        val isLoggedIn = YouTube.cookie != null
        val budget = ProbeBudget(MAX_PROBES_PER_RESOLVE)

        var attempts = 0
        var lastError: Throwable? = null

        for (client in orderedClients()) {
            if (attempts >= MAX_CLIENT_ATTEMPTS) break
            if (client.loginRequired && !isLoggedIn) continue

            attempts += 1

            CapsuleVideoRequestGuard.beforeMetadataRequest()

            val response =
                try {
                    YouTube.player(
                        videoId = videoId,
                        playlistId = null,
                        client = client,
                        signatureTimestamp = signatureTimestamp,
                    ).getOrThrow()
                } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
                    throw blocked
                } catch (throwable: Throwable) {
                    lastError = throwable

                    Timber.tag(TAG).d(
                        throwable,
                        "Video player response failed for ${client.clientName}",
                    )

                    val kind = CapsuleVideoRequestGuard.noteApiFailure(throwable)

                    when (kind) {
                        CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED,
                        CapsuleVideoRequestGuard.FailureKind.BOT_CHECK,
                        CapsuleVideoRequestGuard.FailureKind.FORBIDDEN,
                        ->
                            throw CapsuleVideoRequestGuard.RequestBlockedException(
                                "YouTube VIDEO player request stopped after a " +
                                    "${kind.name.lowercase()} response",
                                throwable,
                            )

                        CapsuleVideoRequestGuard.FailureKind.PERMANENT -> throw throwable

                        else -> continue
                    }
                }

            val status = response.playabilityStatus.status
            if (status != "OK") {
                val reason =
                    response.playabilityStatus.reason
                        ?: "YouTube video is not playable"

                val playabilityError = IllegalStateException(reason)
                lastError = playabilityError

                when (CapsuleVideoRequestGuard.classify("$status $reason")) {
                    CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED,
                    CapsuleVideoRequestGuard.FailureKind.BOT_CHECK,
                    -> {
                        CapsuleVideoRequestGuard.noteApiFailure(playabilityError)
                        throw CapsuleVideoRequestGuard.RequestBlockedException(
                            "YouTube VIDEO playability was blocked by anti-bot/rate limiting",
                            playabilityError,
                        )
                    }

                    /*
                     * Removed, private, region-locked or age-gated clips are
                     * not going to become playable on another client, so stop
                     * instead of spending two more player requests.
                     */
                    CapsuleVideoRequestGuard.FailureKind.PERMANENT -> throw playabilityError

                    else -> continue
                }
            }

            val playerMusicVideoType = response.videoDetails?.musicVideoType
            if (
                playerMusicVideoType != null &&
                playerMusicVideoType != MUSIC_VIDEO_TYPE_OMV
            ) {
                /*
                 * The matcher already decided this id is an OMV. If the player
                 * disagrees, the id itself is wrong: retrying other clients
                 * cannot change that.
                 */
                throw IllegalStateException(
                    "Matched item is not an official YouTube Music video",
                )
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
                throw IllegalStateException(
                    "YouTube player metadata did not match the current song",
                )
            }

            val preferMuxedFirst = quality == CapsuleVideoQuality.P360

            val resolved =
                if (preferMuxedFirst || !adaptiveAllowed) {
                    tryMuxed(
                        sourceMediaId = sourceMediaId,
                        videoId = videoId,
                        response = response,
                        client = client,
                        quality = quality,
                        budget = budget,
                    ) ?: if (adaptiveAllowed) {
                        tryAdaptive(
                            sourceMediaId = sourceMediaId,
                            videoId = videoId,
                            response = response,
                            client = client,
                            quality = quality,
                            budget = budget,
                        )
                    } else {
                        null
                    }
                } else {
                    tryAdaptive(
                        sourceMediaId = sourceMediaId,
                        videoId = videoId,
                        response = response,
                        client = client,
                        quality = quality,
                        budget = budget,
                    ) ?: tryMuxed(
                        sourceMediaId = sourceMediaId,
                        videoId = videoId,
                        response = response,
                        client = client,
                        quality = quality,
                        budget = budget,
                    )
                }

            if (resolved != null) {
                cache[key] = resolved
                if (adaptiveAllowed) latestCacheKeyByVideoId[videoId] = key
                preferredClient = client

                Timber.tag(TAG).i(
                    "Resolved $videoId as " +
                        (if (resolved.audioStreamUrl != null) "adaptive" else "muxed") +
                        " ${resolved.videoFormat.height ?: 0}p via ${client.clientName}",
                )

                return@runCatching resolved
            }

            lastError =
                lastError
                    ?: IllegalStateException(
                        "No compatible stream from ${client.clientName}",
                    )
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
        budget: ProbeBudget,
    ): Cached? {
        val videoFormats = selectAdaptiveVideoFormats(response, quality)
        val audioFormats = selectAdaptiveAudioFormats(response)
        if (videoFormats.isEmpty() || audioFormats.isEmpty()) return null

        var audioChoice: Pair<PlayerResponse.StreamingData.Format, String>? = null
        for (audioFormat in audioFormats.take(2)) {
            val url = resolveAndValidate(audioFormat, videoId, client, budget) ?: continue
            audioChoice = audioFormat to url
            break
        }
        val (audioFormat, audioUrl) = audioChoice ?: return null

        for (videoFormat in videoFormats.take(2)) {
            val videoUrl = resolveAndValidate(videoFormat, videoId, client, budget) ?: continue
            return Cached(
                sourceMediaId = sourceMediaId,
                videoId = videoId,
                videoStreamUrl = videoUrl,
                videoFormat = videoFormat,
                audioStreamUrl = audioUrl,
                audioFormat = audioFormat,
                expiresAtMs = expiry(response),
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
        budget: ProbeBudget,
    ): Cached? {
        for (format in selectMuxedFormats(response, quality).take(2)) {
            val url = resolveAndValidate(format, videoId, client, budget) ?: continue
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
        budget: ProbeBudget,
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

        /*
         * Out of probe budget: hand the URL over unverified. One playback error
         * is cheaper than another round of range requests, and ExoPlayer's own
         * error path already falls back.
         */
        if (!budget.consume()) return url

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
        /*
         * YouTube Music's Videos shelf is the primary matcher. This second pass
         * is deliberately a sanity check, not another exact matcher.
         *
         * Official channels may be called "ArtistVEVO", a label name, or a
         * localized channel name, so author mismatch alone must not reject a
         * valid OMV.
         */
        details ?: return true

        val expectedTitleNorm = normalizeTitleForCheck(expectedTitle)
        val actualTitleNorm = normalizeTitleForCheck(details.title)

        if (expectedTitleNorm.isNotBlank() && actualTitleNorm.isNotBlank()) {
            val overlap = titleTokenOverlapForCheck(expectedTitleNorm, actualTitleNorm)
            val titleLooksRelated =
                actualTitleNorm == expectedTitleNorm ||
                    actualTitleNorm.contains(expectedTitleNorm) ||
                    expectedTitleNorm.contains(actualTitleNorm) ||
                    overlap >= 0.50

            if (!titleLooksRelated) return false
        }

        val actualRaw = " ${normalizeForCheck(details.title)} "
        val expectedRaw = " ${normalizeForCheck(expectedTitle)} "
        val rejectTokens =
            listOf(
                " live ",
                " concert ",
                " performance ",
                " acoustic ",
                " cover ",
                " karaoke ",
                " lyric ",
                " lyrics ",
                " visualizer ",
                " slowed ",
                " reverb ",
                " remix ",
                " fanmade ",
                " fan made ",
                " amv ",
                " reaction ",
                " interview ",
                " behind the scenes ",
                " shorts ",
            )

        if (
            rejectTokens.any { token ->
                actualRaw.contains(token) &&
                    !expectedRaw.contains(token)
            }
        ) {
            return false
        }

        val expectedDuration = expectedDurationSeconds?.takeIf { it > 0 }
        val actualDuration = details.lengthSeconds.toIntOrNull()?.takeIf { it > 0 }
        if (expectedDuration != null && actualDuration != null) {
            /*
             * Music videos can contain intros/outros, so allow a larger window
             * here. The shelf matcher already applied the tighter duration score.
             */
            if (kotlin.math.abs(expectedDuration - actualDuration) > 110) return false
        }

        return true
    }

    private fun titleTokenOverlapForCheck(
        left: String,
        right: String,
    ): Double {
        val a = left.split(' ').filter { it.length > 1 }.toSet()
        val b = right.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0

        return a.intersect(b).size.toDouble() /
            a.union(b).size.toDouble()
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

    private suspend fun validate(
        url: String,
        fallbackUserAgent: String,
    ): Boolean {
        CapsuleVideoRequestGuard.beforeStreamProbe()

        return try {
            withContext(Dispatchers.IO) {
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
                        val kind = CapsuleVideoRequestGuard.noteStreamStatus(response.code)

                        if (kind == CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED) {
                            throw CapsuleVideoRequestGuard.RequestBlockedException(
                                "YouTube VIDEO stream validation returned HTTP ${response.code}",
                            )
                        }

                        /*
                         * A lone 403 here means "this format/URL is not usable",
                         * not "we are being throttled". Reject the format and
                         * let the caller try the next one; the guard only trips
                         * once these repeat.
                         */
                        response.code in 200..399 || response.code == 416
                    }
            }
        } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
            throw blocked
        } catch (throwable: Throwable) {
            Timber.tag(TAG).d(
                throwable,
                "Video stream probe failed",
            )
            false
        }
    }

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
