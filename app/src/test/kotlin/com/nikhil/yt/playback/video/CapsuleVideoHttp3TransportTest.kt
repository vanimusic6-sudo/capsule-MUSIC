package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoHttp3TransportTest {
    @Test fun debugOnAndroid17SelectsOnlyActualGooglevideoSubdomains() {
        assertTrue(shouldUseVideoHttp3Transport(true, 37, false, "rr2---sn-42.googlevideo.com"))
        assertTrue(shouldUseVideoHttp3Transport(true, 34, false, "GVS.GOOGLEVIDEO.COM"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, false, "googlevideo.com"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, false, "badgooglevideo.com"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, false, "gvs.googlevideo.com.evil.org"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, false, "music.youtube.com"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, false, null))
    }

    @Test fun productionAndAnyConfiguredProxyRetainVerifiedOkHttp() {
        assertFalse(shouldUseVideoHttp3Transport(false, 37, false, "r.googlevideo.com"))
        assertFalse(shouldUseVideoHttp3Transport(true, 37, true, "r.googlevideo.com"))
        assertFalse(shouldUseVideoHttp3Transport(true, 33, false, "r.googlevideo.com"))
    }
}
