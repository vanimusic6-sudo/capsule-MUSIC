package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import android.os.Bundle
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CapsuleVideoIpcBoundaryTest {
    @Test fun acceptsNormalOfficialCdnUrls() {
        assertTrue(CapsuleVideoIpc.isSafeStreamUrl(
            "https://rr5---sn-abcdef.googlevideo.com/videoplayback?expire=123&sig=abc"
        ))
        assertTrue(CapsuleVideoIpc.isSafeStreamUrl(
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/xyz"
        ))
    }

    @Test fun rejectsFileContentHttpAndLocalUrls() {
        for (url in listOf(
            "file:///data/data/com.nikhil.yt/shared_prefs/settings.xml",
            "content://com.nikhil.yt.FileProvider/private",
            "http://example.org/video.mp4",
            "https://user:pass@video.example.com/file",
            "https://localhost/video.mp4",
            "https://127.0.0.1/video.mp4",
            "https://192.168.1.1/video.mp4",
            "https://video.example.com:8080/video.mp4",
            "https://example.org/" + "a".repeat(17_000)
        )) assertFalse(url, CapsuleVideoIpc.isSafeStreamUrl(url))
    }
    @Test fun nativeSabrSpecRoundTripsThroughIsolatedExtractorIpc() {
        val spec = com.nikhil.yt.innertube.video.CapsuleSabrVideoSpec(
            ustreamerConfigBase64 = "AQIDBAUGBwg=",
            videoItag = 136, videoLastModified = 170000000L, videoLength = 48_000_000L,
            audioItag = 140, audioLastModified = 170000000L, audioLength = 3_840_000L,
            clientName = 101, clientVersion = "1.2.0", durationMs = 240_000L,
        )
        val model = CapsuleResolvedVideo(
            videoId = "abcdefghijk",
            videoUrl = "https://rr.googlevideo.com/videoplayback?expire=1999999999",
            qualityLabel = "720p SABR", width = 1280, height = 720,
            videoItag = 136, audioItag = 140, expiresAtMs = 1_999_999_999_000L,
            delivery = com.nikhil.yt.innertube.video.CapsuleVideoDelivery.SABR,
            userAgent = "VisionOS", sabrSpec = spec,
        )
        val delivered = CapsuleVideoIpc.resolvedFromBundle(
            CapsuleVideoIpc.successBundle(model), model.videoId, 1_800_000_000_000L,
        )
        assertNotNull(delivered)
        assertTrue(delivered!!.sabrSpec!!.isValid())
        assertTrue(delivered.delivery == com.nikhil.yt.innertube.video.CapsuleVideoDelivery.SABR)

        val missing = CapsuleVideoIpc.successBundle(model).apply {
            remove(CapsuleVideoIpc.EXTRA_SABR)
        }
        assertNull(CapsuleVideoIpc.resolvedFromBundle(missing, model.videoId, 1_800_000_000_000L))
        val corrupt = CapsuleVideoIpc.successBundle(model)
        corrupt.getBundle(CapsuleVideoIpc.EXTRA_SABR)!!.putLong("v_length", Long.MAX_VALUE)
        assertNull(CapsuleVideoIpc.resolvedFromBundle(corrupt, model.videoId, 1_800_000_000_000L))
    }

    @Test fun consumerRechecksUntrustedBundlesBeforeCachingThem() {
        fun response() = Bundle().apply {
            putBoolean(CapsuleVideoIpc.EXTRA_SUCCESS, true)
            putString(CapsuleVideoIpc.EXTRA_VIDEO_ID, "abcdefghijk")
            putString(CapsuleVideoIpc.EXTRA_VIDEO_URL, "https://rr.googlevideo.com/video")
            putLong(CapsuleVideoIpc.EXTRA_EXPIRES_AT, 1_000_000L)
        }
        assertNotNull(CapsuleVideoIpc.resolvedFromBundle(response(), "abcdefghijk", 100L))
        assertNull(CapsuleVideoIpc.resolvedFromBundle(response(), "otherclip12", 100L))
        for ((field, value) in listOf(
            CapsuleVideoIpc.EXTRA_VIDEO_URL to "https://172.16.0.1/private",
            CapsuleVideoIpc.EXTRA_AUDIO_URL to "https://[::1]/private",
            CapsuleVideoIpc.EXTRA_DELIVERY to "SABR",
            CapsuleVideoIpc.EXTRA_USER_AGENT to "agent\r\nCookie: secret",
        )) {
            val bad = response().apply { putString(field, value) }
            assertNull(CapsuleVideoIpc.resolvedFromBundle(bad, "abcdefghijk", 100L))
        }
        val expired = response().apply { putLong(CapsuleVideoIpc.EXTRA_EXPIRES_AT, 100L) }
        assertNull(CapsuleVideoIpc.resolvedFromBundle(expired, "abcdefghijk", 100L))
    }

}
