
 /** Capsule MUSIC
 * Dedicated YouTube progressive-video resolver.
 *
 * Important: this file does not modify or wrap Capsule's normal
 * YTPlayerUtils.playerResponseForPlayback() audio pipeline.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
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
        val videoId: String,
        val streamUrl: String,
        val format: PlayerResponse.StreamingData.Format,
        val expiresAtMs: Long,
    ) {
        val qualityLabel: String
            get() = format.qualityLabel
                ?: format.height?.let { "${it}p" }
                ?: format.quality.ifBlank { "VIDEO" }
    }

    private data class Cached(
        val value: ResolvedVideo,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var clientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    private val videoClients: List<YouTubeClient> =
        listOf(
            IOS,
            TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            WEB,
            WEB_REMIX,
        ).distinct()

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolve(videoId: String): Result<ResolvedVideo> = runCatching {
        val now = System.currentTimeMillis()
        cache[videoId]
            ?.takeIf { it.expiresAtMs > now + CACHE_SAFETY_MS }
            ?.value
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
                    Timber.tag(TAG).d(it, "Video player response failed for ${client.clientName}")
                }.getOrNull() ?: continue

            if (response.playabilityStatus.status != "OK") {
                lastError = IllegalStateException(
                    response.playabilityStatus.reason
                        ?: "YouTube video is not playable",
                )
                continue
            }

            val formats = selectProgressiveFormats(response)
            if (formats.isEmpty()) {
                lastError = IllegalStateException(
                    "No progressive audio+video format returned by ${client.clientName}",
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

                url = StreamClientUtils.patchClientVersion(
                    url,
                    client.clientVersion,
                )

                if (!validate(url, client.userAgent)) {
                    continue
                }

                val expiresSeconds = response.streamingData?.expiresInSeconds ?: 300
                val expiresAtMs =
                    System.currentTimeMillis() +
                        expiresSeconds.coerceAtLeast(60) * 1000L

                val resolved =
                    ResolvedVideo(
                        videoId = videoId,
                        streamUrl = url,
                        format = format,
                        expiresAtMs = expiresAtMs,
                    )

                cache[videoId] = Cached(resolved, expiresAtMs)
                Timber.tag(TAG).i(
                    "Resolved $videoId as ${resolved.qualityLabel} via ${client.clientName}",
                )
                return@runCatching resolved
            }
        }

        throw lastError
            ?: IllegalStateException("Could not resolve a playable YouTube video stream")
    }

    private fun selectProgressiveFormats(
        response: PlayerResponse,
    ): List<PlayerResponse.StreamingData.Format> {
        val formats = response.streamingData?.formats.orEmpty()

        val candidates =
            formats
                .asSequence()
                .filter { format ->
                    format.width != null &&
                        format.height != null &&
                        format.bitrate > 0 &&
                        (
                            format.url != null ||
                                format.signatureCipher != null ||
                                format.cipher != null
                        )
                }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        val preferred = candidates.filter { (it.height ?: 0) <= 1080 }
        val source = preferred.ifEmpty { candidates }

        return source.sortedWith(
            compareByDescending<PlayerResponse.StreamingData.Format> {
                it.mimeType.startsWith("video/mp4", ignoreCase = true)
            }.thenByDescending {
                it.url != null
            }.thenByDescending {
                it.height ?: 0
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
                    .header("Range", "bytes=0-1")
                    .apply {
                        originReferer.origin?.let { header("Origin", it) }
                        originReferer.referer?.let { header("Referer", it) }
                    }
                    .build()

            currentClient().newCall(request).execute().use { response ->
                response.code in 200..399 || response.code == 416
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
