package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrobbleCoordinatorTest {
    @Test
    fun finishedListenWindowUsesActualPlayedDuration() {
        assertEquals(7_000L to 10_000L, finishedListenWindow(endMs = 10_000L, totalPlayTimeMs = 3_000L))
    }

    @Test
    fun finishedListenWindowPreservesZeroDuration() {
        assertEquals(10_000L to 10_000L, finishedListenWindow(endMs = 10_000L, totalPlayTimeMs = 0L))
    }
}
