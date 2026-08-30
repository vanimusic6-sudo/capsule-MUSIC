
 /** Capsule MUSIC
 * Dedicated YouTube Music video resolver.
 *
 * The normal Capsule audio pipeline is not modified here.
 * VIDEO first asks YouTube Music for the linked official music video,
 * then resolves a muxed progressive stream for that exact video id.
 *
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.YouTubeMusicVideoLinkResolver
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
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
        val streamUrl: String,
        val format: PlayerResponse.StreamingData.Format,
        val expiresAtMs: Long,
    ) {
        val qualityLabel: String
            get() =
                format.qualityLabel
                    ?: format.height?.let { "${it}p" }
                    ?: format.quality.ifBlank { "VIDEO" }
    }

    private data class Cached(
        val videoId: String,
        val streamUrl: String,
        val format: PlayerResponse.StreamingData.Format,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var clientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    /*
     * WEB is intentionally first: for ordinary VODs it is the most useful
     * client for legacy muxed audio+video formats (for example itag 18).
     * VIDEO is isolated, so changing this order cannot affect Capsule audio.
     */
    private val videoClients: List<YouTubeClient> =
        listOf(
            WEB,
            TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            IOS,
        ).distinct()

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * YouTube Music-style resolution:
     * canonical song id -> currentVideoEndpoint -> official OMV -> stream.
     *
     * There is intentionally no text/title search and setVideoId is not used.
     */
    suspend fun resolveForSong(sourceMediaId: String): Result<ResolvedVideo> = runCatching {
        val canonicalId = sourceMediaId.trim()
        require(canonicalId.isNotBlank()) { "Missing YouTube Music track id" }

        val link =
            YouTubeMusicVideoLinkResolver
                .resolve(canonicalId)
                .getOrThrow()

        val stream =
            resolveLinkedVideo(link.videoId)
                .getOrThrow()

        ResolvedVideo(
            sourceMediaId = canonicalId,
            videoId = link.videoId,
            streamUrl = stream.streamUrl,
            format = stream.format,
            expiresAtMs = stream.expiresAtMs,
        )
    }

    /**
     * Used by the playback DataSource after resolveForSong has already warmed
     * the cache. It can still resolve again if the cached URL expired.
     */
    suspend fun resolve(videoId: String): Result<ResolvedVideo> = runCatching {
        val linkedId = videoId.trim()
        require(linkedId.isNotBlank()) { "Missing linked YouTube video id" }

        val stream = resolveLinkedVideo(linkedId).getOrThrow()
        ResolvedVideo(
            sourceMediaId = linkedId,
            videoId = linkedId,
            streamUrl = stream.streamUrl,
            format = stream.format,
            expiresAtMs = stream.expiresAtMs,
        )
    }

    private suspend fun resolveLinkedVideo(videoId: String): Result<Cached> = runCatching {
        val now = System.currentTimeMillis()

        cache[videoId]
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?.let { return@runCatching it }

        val signatureTimestamp =
            NewPipeUtils.getSignatureTimestamp(videoId)
                .getOrNull()

        var lastError: Throwable? = null

        for (client in videoClients) {
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

            /*
             * currentVideoEndpoint already gave us OMV, but the player response
             * can independently confirm it. If the field is absent we do not
             * reject the stream; if it explicitly says something else we do.
             */
            val playerMusicVideoType = response.videoDetails?.musicVideoType
            if (
                playerMusicVideoType != null &&
                playerMusicVideoType != MUSIC_VIDEO_TYPE_OMV
            ) {
                lastError =
                    IllegalStateException(
                        "Linked item is not an official YouTube Music video",
                    )
                continue
            }

            val formats = selectMuxedFormats(response)
            if (formats.isEmpty()) {
                lastError =
                    IllegalStateException(
                        "No compatible audio+video stream returned by ${client.clientName}",
                    )
                continue
            }

            for (format in formats.take(8)) {
                var url =
                    NewPipeUtils.getStreamUrl(
                        format = format,
                        videoId = videoId,
                        client = client,
                    ).getOrNull() ?: continue

                url =
                    StreamClientUtils.patchClientVersion(
                        url,
                        client.clientVersion,
                    )

                if (!validate(url, client.userAgent)) {
                    continue
                }

                val expiresSeconds =
                    response.streamingData?.expiresInSeconds ?: 300

                val expiresAtMs =
                    System.currentTimeMillis() +
                        expiresSeconds.coerceAtLeast(60) * 1000L

                val cached =
                    Cached(
                        videoId = videoId,
                        streamUrl = url,
                        format = format,
                        expiresAtMs = expiresAtMs,
                    )

                cache[videoId] = cached

                Timber.tag(TAG).i(
                    "Resolved official video $videoId as ${
                        format.qualityLabel ?: format.height?.let { "${it}p" } ?: format.quality
                    } via ${client.clientName}",
                )

                return@runCatching cached
            }
        }

        throw lastError
            ?: IllegalStateException(
                "Could not resolve a compatible official YouTube Music video stream",
            )
    }

    private fun selectMuxedFormats(
        response: PlayerResponse,
    ): List<PlayerResponse.StreamingData.Format> {
        val formats = response.streamingData?.formats.orEmpty()

        val candidates =
            formats
                .asSequence()
                .filter { format ->
                    val hasVideo =
                        format.width != null &&
                            format.height != null

                    val hasAudio =
                        (format.audioChannels ?: 0) > 0 ||
                            format.audioQuality != null ||
                            format.mimeType.contains("mp4a", ignoreCase = true) ||
                            format.mimeType.contains("opus", ignoreCase = true)

                    hasVideo &&
                        hasAudio &&
                        format.bitrate > 0 &&
                        (
                            format.url != null ||
                                format.signatureCipher != null ||
                                format.cipher != null
                        )
                }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        /*
         * Prefer 720p muxed when YouTube offers it, then 480/360.
         * 1080p is normally adaptive-only and should not outrank a stable
         * muxed format just because the numeric height is larger.
         */
        fun qualityRank(format: PlayerResponse.StreamingData.Format): Int =
            when (format.height ?: 0) {
                in 700..800 -> 4
                in 470..699 -> 3
                in 350..469 -> 2
                in 1..349 -> 1
                else -> 0
            }

        return candidates.sortedWith(
            compareByDescending<PlayerResponse.StreamingData.Format> {
                it.mimeType.startsWith("video/mp4", ignoreCase = true)
            }.thenByDescending {
                qualityRank(it)
            }.thenByDescending {
                it.url != null
            }.thenByDescending {
                it.bitrate
            },
        )
    }

    private fun validate(
        url: String,
        fallbackUserAgent: String,
    ): Boolean {
        return runCatching {
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
                    response.code in 200..399 ||
                        response.code == 416
                }
        }.getOrDefault(false)
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
