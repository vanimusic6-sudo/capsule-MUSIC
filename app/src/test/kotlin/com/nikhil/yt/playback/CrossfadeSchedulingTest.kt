package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeSchedulingTest {
    @Test
    fun farFromFadeSleepsWithoutMissingThePreloadWindow() {
        assertEquals(10_000L, crossfadeWaitBeforePreloadMs(120_000L, 7_200L))
        assertEquals(350L, crossfadeWaitBeforePreloadMs(7_550L, 7_200L))
    }

    @Test
    fun nearFadeKeepsTheFineClock() {
        assertEquals(100L, crossfadeWaitBeforePreloadMs(7_200L, 7_200L))
        assertEquals(100L, crossfadeWaitBeforePreloadMs(4_000L, 7_200L))
    }
}
