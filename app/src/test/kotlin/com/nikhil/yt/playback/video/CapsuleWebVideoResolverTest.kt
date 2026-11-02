package com.nikhil.yt.playback.video

import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrVideoVariant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class CapsuleWebVideoResolverTest {
    private val bootstrap = SabrBootstrap(
        videoId = "abcdefghijk",
        serverAbrStreamingUrl = "https://rr.googlevideo.com/videoplayback?expire=4102444800&n=deciphered",
        videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3, 4),
        clientName = 67, clientVersion = "1.20260707.12.00",
        audioFormat = SabrFormatId(140, 100L, "b3JpZ2luYWw="),
        discardVideoFormat = SabrFormatId(160, 100L), discardVideoHeight = 144,
        selectedVideoFormat = SabrFormatId(247, 100L), selectedVideoWidth = 1280,
        selectedVideoHeight = 720, selectedVideoContentLengthBytes = 5_000_000L,
        selectedVideoMimeType = "video/webm; codecs=\"vp9\"",
        durationMs = 240_000L, contentLengthBytes = 1_000_000L,
        mimeType = "audio/mp4", poToken = byteArrayOf(8, 7, 6),
        requestUserAgent = "Web Remix", requestOrigin = "https://music.youtube.com",
        videoVariants = listOf(
            SabrVideoVariant(SabrFormatId(136, 100L), 1280, 720, 500_000,
                "video/mp4; codecs=\"avc1.4d401f\"", 4_000_000L),
            SabrVideoVariant(SabrFormatId(135, 100L), 854, 480, 200_000,
                "video/mp4; codecs=\"avc1.4d401e\"", 2_000_000L),
        ),
    )
    private fun stream(b: SabrBootstrap = bootstrap) = ExtractedStream(
        videoId = b.videoId, audioUrl = "sabr://abcdefghijk", headers = emptyMap(),
        loudnessDb = null, expiresAt = null, contentLengthBytes = b.contentLengthBytes,
        itag = 140, mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128_000,
        sampleRate = 44100, clientName = "WEB_REMIX", profileId = "WEB_REMIX_SABR__po",
        requireBoundedRange = false, rangeChunkSizeBytes = 1_048_576,
        sabrBootstrap = b, sabrVideoBootstrap = b,
    )

    @Test fun directWebStreamsKeepTokenizedUrlsAndRequiredHeadersWithoutLeakingCookies() {
        val headers = mapOf("User-Agent" to "Web Remix", "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/", "Cookie" to "private")
        val direct = ExtractedStream(
            videoId = "abcdefghijk", audioUrl = "https://rr.googlevideo.com/videoplayback?pot=audio-token",
            headers = headers, loudnessDb = null,
            expiresAt = kotlin.time.Instant.parse("2100-01-01T00:00:00Z"),
            contentLengthBytes = 1000L, itag = 140, mimeType = "audio/mp4", codecs = "mp4a.40.2",
            bitrate = 128000, sampleRate = 44100, clientName = "WEB_REMIX", profileId = "WEB_REMIX__po",
            requireBoundedRange = true, rangeChunkSizeBytes = 1_048_576L,
            videoUrl = "https://rr.googlevideo.com/videoplayback?pot=video-token", videoWidth = 1280,
            videoHeight = 720, videoItag = 136,
        )
        val resolved = tokenBackedVideo(direct, 720)
        assertEquals(direct.videoUrl, resolved.videoUrl)
        assertEquals(direct.audioUrl, resolved.audioUrl)
        assertEquals("https://music.youtube.com", resolved.requestHeaders["Origin"])
        assertFalse(resolved.requestHeaders.containsKey("Cookie"))
        assertNull(resolved.sabrSpec)
        assertEquals(com.nikhil.yt.innertube.video.CapsuleVideoDelivery.PROGRESSIVE, resolved.delivery)
    }

    @Test fun choosesSessionAdvertisedH264AndCarriesExactTokenOriginAndTaggedAudio() {
        val resolved = tokenBackedVideo(stream(), 720)
        assertEquals(136, resolved.videoItag)
        assertEquals(140, resolved.audioItag)
        assertEquals("CAcG", resolved.sabrSpec!!.poTokenBase64)
        assertEquals("b3JpZ2luYWw=", resolved.sabrSpec!!.audioXtags)
        assertEquals("https://music.youtube.com", resolved.sabrSpec!!.requestOrigin)
        assertTrue(resolved.sabrSpec!!.isValid())
        val received = CapsuleVideoIpc.resolvedFromBundle(CapsuleVideoIpc.successBundle(resolved), resolved.videoId)
        assertEquals(resolved.sabrSpec, received!!.sabrSpec)
    }

    @Test fun respectsQualityCapWhenTheLibraryInitiallySelectedAHigherVariant() {
        val resolved = tokenBackedVideo(stream(), 480)
        assertEquals(135, resolved.videoItag)
        assertEquals(480, resolved.height)
    }

    @Test fun rejectsUnsupportedContainersUnsafeEndpointsAndUntrustedOrigin() {
        assertThrows(IllegalArgumentException::class.java) { tokenBackedVideo(stream(bootstrap.copy(mimeType = "audio/webm")), 720) }
        assertThrows(IllegalArgumentException::class.java) { tokenBackedVideo(stream(bootstrap.copy(serverAbrStreamingUrl = "https://127.0.0.1/private")), 720) }
        assertThrows(IllegalArgumentException::class.java) { tokenBackedVideo(stream(bootstrap.copy(requestOrigin = "https://evil.test")), 720) }
    }
}
