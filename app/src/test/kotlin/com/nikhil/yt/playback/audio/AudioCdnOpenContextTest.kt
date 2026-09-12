package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCdnOpenContextTest {
    @Test
    fun freshResolvedUrlFinishesSmallSettleWindow() {
        assertEquals(250L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_000L, resolvedAtElapsedMs = 1_000L))
        assertEquals(150L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_100L, resolvedAtElapsedMs = 1_000L))
        assertEquals(0L, audioCdnInitialSettleDelayMs(nowElapsedMs = 1_250L, resolvedAtElapsedMs = 1_000L))
        assertEquals(0L, audioCdnInitialSettleDelayMs(nowElapsedMs = 5_000L, resolvedAtElapsedMs = 0L))
    }

    @Test
    fun onlyHttp2CrossHostRouteCountsAsCoalesced() {
        assertTrue(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr1.googlevideo.com", "h2"))
        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr2.googlevideo.com", "h2"))
        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", "rr1.googlevideo.com", "http/1.1"))
        assertFalse(audioCdnCrossHostCoalesced("rr2.googlevideo.com", null, "h2"))
    }
}
