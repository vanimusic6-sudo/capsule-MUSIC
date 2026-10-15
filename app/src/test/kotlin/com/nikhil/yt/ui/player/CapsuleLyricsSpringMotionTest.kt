package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLyricsSpringMotionTest {
    @Test fun edgeReturnsExactlyToRestAndNeverSeparatesFromSheet() {
        assertEquals(0f, capsuleLyricsEdgeRecoil(0f, 0f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(1f, 1f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(0.5f, 0.5f), 0.00001f)
        assertTrue(capsuleLyricsEdgeRecoil(0.5f, 0.25f) < 0f)
        assertTrue(capsuleLyricsEdgeRecoil(0.5f, 0.75f) > 0f)
        for (b in -20..120) for (l in -20..120 step 5) {
            val pull = capsuleLyricsEdgeRecoil(b / 100f, l / 100f)
            assertTrue("recoil must stay below physical GPU budget", kotlin.math.abs(pull) <= 0.26f)
        }
    }
}
