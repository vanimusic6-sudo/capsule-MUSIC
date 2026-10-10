package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import java.util.Collections
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoStreamProbeTest {
    private fun video(
        url: String,
        audioUrl: String? = null,
    ) = YouTubeVideoResolver.ResolvedVideo(
        sourceMediaId = "song",
        videoId = "video-id",
        videoStreamUrl = url,
        videoFormat = YouTubeVideoResolver.StreamFormat(
            itag = 137, width = 1920, height = 1080, qualityLabel = "1080p",
        ),
        audioStreamUrl = audioUrl,
        audioFormat = audioUrl?.let {
            YouTubeVideoResolver.StreamFormat(itag = 140, width = null, height = null, qualityLabel = null)
        },
        expiresAtMs = Long.MAX_VALUE,
    )

    private fun client(block: (Request) -> Unit) =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                block(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .body("ready".toResponseBody())
                    .build()
            })
            .build()

    @Test
    fun reachableAdaptiveVideoChecksBothVideoAndAudioStreams() = runBlocking {
        val checked = Collections.synchronizedList(mutableListOf<String>())
        val adaptive = video(
            "https://video.example.test/stream",
            "https://audio.example.test/stream",
        )
        val probe = CapsuleVideoStreamProbe(
            httpClient = client { request ->
                checked += request.url.host
                assertEquals("bytes=0-1023", request.header("Range"))
            },
            resolveMuxed = { _, _ -> error("Fallback should not run") },
        )
        assertEquals(adaptive, probe.prepare(adaptive, CapsuleVideoQuality.P1080).getOrThrow())
        assertEquals(listOf("video.example.test", "audio.example.test"), checked)
    }

    @Test
    fun tlsFailureOnMuxedNeverPublishesUnreachableVideo() = runBlocking {
        val muxed = video("https://blocked.example.test/stream")
        val probe = CapsuleVideoStreamProbe(
            httpClient = client { throw SSLHandshakeException("connection closed") },
            resolveMuxed = { _, _ -> error("Muxed stream must not trigger a fallback") },
        )
        val result = probe.prepare(muxed, CapsuleVideoQuality.AUTO)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SSLHandshakeException)
    }

    @Test
    fun adaptiveTlsFailureCanSelectOneWorkingMuxedFallback() = runBlocking {
        val adaptive = video("https://good.example.test/video", "https://blocked.example.test/audio")
        val muxed = video("https://good.example.test/muxed")
        var fallbacks = 0
        val probe = CapsuleVideoStreamProbe(
            httpClient = client { request ->
                if (request.url.host == "blocked.example.test") {
                    throw SSLHandshakeException("connection closed")
                }
            },
            resolveMuxed = { _, _ ->
                fallbacks += 1
                Result.success(muxed)
            },
        )
        val chosen = probe.prepare(adaptive, CapsuleVideoQuality.P1080).getOrThrow()
        assertEquals(muxed, chosen)
        assertFalse(chosen.isAdaptive)
        assertEquals(1, fallbacks)
    }
}
