/*
 * Capsule MUSIC: VIDEO-only transport readiness gate.
 *
 * Signed stream URLs can resolve successfully even while CDN TLS handshakes
 * fail. Before replacing a working AUDIO item, verify that the required VIDEO
 * byte stream(s) are actually reachable from the device.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

internal class CapsuleVideoStreamProbe(
    httpClient: OkHttpClient,
    private val resolveMuxed: suspend (String, CapsuleVideoQuality) ->
        Result<YouTubeVideoResolver.ResolvedVideo> = { id, quality ->
            YouTubeVideoResolver.resolveMuxed(id, quality)
        },
) {
    // Same proxy, TLS settings and connection pool as the real VIDEO client.
    // Only the readiness check has a short, explicit time budget.
    private val client = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun prepare(
        video: YouTubeVideoResolver.ResolvedVideo,
        quality: CapsuleVideoQuality,
    ): Result<YouTubeVideoResolver.ResolvedVideo> = withContext(Dispatchers.IO) {
        try {
            verifyStreams(video)
            Result.success(video)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: IOException) {
            // Adaptive playback needs two working TLS connections. On fragile
            // networks, one muxed stream can succeed when the pair cannot.
            // One fallback maximum; real 403/429 must never spawn more probes.
            if (!video.isAdaptive || CapsuleVideoRequestGuard.isBlocked() ||
                firstFailure.message?.contains("HTTP 403") == true ||
                firstFailure.message?.contains("HTTP 429") == true
            ) {
                Timber.tag("CapsuleVideo").w(
                    "VIDEO CDN preflight failed: %s", firstFailure.javaClass.simpleName,
                )
                return@withContext Result.failure(firstFailure)
            }

            val muxed = try {
                resolveMuxed(video.videoId, quality).getOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            if (muxed == null || muxed.isAdaptive ||
                muxed.videoStreamUrl == video.videoStreamUrl
            ) {
                return@withContext Result.failure(firstFailure)
            }

            try {
                verifyStreams(muxed)
                Timber.tag("CapsuleVideo").i(
                    "Adaptive VIDEO CDN failed; verified muxed fallback %s",
                    muxed.qualityLabel,
                )
                Result.success(muxed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fallbackFailure: IOException) {
                Result.failure(fallbackFailure)
            }
        }
    }

    private fun verifyStreams(video: YouTubeVideoResolver.ResolvedVideo) {
        verifyUrl(video.videoStreamUrl, "video")
        if (video.isAdaptive) {
            verifyUrl(requireNotNull(video.audioStreamUrl), "adaptive-audio")
        }
    }

    private fun verifyUrl(url: String, kind: String) {
        // Use GET, not HEAD, because signed googlevideo URLs may be method-
        // sensitive. Only one byte is read; the rest is never downloaded.
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1023")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("VIDEO $kind CDN HTTP ${response.code}")
            }
            if ((response.body?.byteStream()?.read() ?: -1) == -1) {
                throw EOFException("VIDEO $kind CDN returned an empty body")
            }
        }
    }
}
