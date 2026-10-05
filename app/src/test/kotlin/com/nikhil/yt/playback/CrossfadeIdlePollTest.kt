package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeIdlePollTest {
    @Test fun `long tracks sleep longer but approach the preload window precisely`() {
        val preloadWindow = 4_200L
        assertEquals(500L, crossfadeIdlePollDelay(180_000L, preloadWindow))
        assertEquals(150L, crossfadeIdlePollDelay(4_500L, preloadWindow))
        assertEquals(100L, crossfadeIdlePollDelay(preloadWindow, preloadWindow))

        for (remaining in 4_300L..6_000L step 100L) {
            val delay = crossfadeIdlePollDelay(remaining, preloadWindow)
            assertTrue("poll must not jump across the preload boundary", delay <= remaining - preloadWindow)
        }
    }
}
