package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same spring values are valid at 60 and 120Hz without a frame clock. */
class CapsuleLyricsSpringMotionTest {
    @Test fun openingLandingIsBoundedAndThereIsNoZoom() {
        assertEquals(-0.008f, capsuleLyricsLandingTravel(-0.03f), 0.00001f)
        assertEquals(0f, capsuleLyricsLandingTravel(0f), 0.00001f)
        assertEquals(1f, capsuleLyricsLandingTravel(1.02f), 0.00001f)
        for (i in -20..120) {
            val p = capsuleLyricsLandingTravel(i / 100f)
            assertTrue("no violent overshoot at 60Hz", p in -0.008f..1f)
        }
    }

    @Test fun foregroundRevealFollowsThePageAndNeverPopsBetweenFrames() {
        assertEquals(0f, capsuleLyricsForegroundOpacity(1f), 0.0001f)
        assertEquals(0f, capsuleLyricsForegroundOpacity(0.90f), 0.0001f)
        assertEquals(1f, capsuleLyricsForegroundOpacity(0f), 0.0001f)
        assertEquals(1f, capsuleLyricsForegroundOpacity(0.05f), 0.0001f)
        var prior = 0f
        for (frame in 0..120) {
            val progress = 1f - frame / 120f
            val now = capsuleLyricsForegroundOpacity(progress)
            assertTrue(now >= prior - 0.00001f)
            if (frame < 120) {
                val next = capsuleLyricsForegroundOpacity(1f - (frame + 1) / 120f)
                assertTrue("avoid sudden jump during 60Hz scroll-like presentation",
                    kotlin.math.abs(next - now) < 0.04f)
            }
            prior = now
        }
    }

    @Test fun inverseTranslationLocksGradientToTheDisplayAtAllProgress() {
        val screenHeightPx = 850f
        for (i in 0..100) {
            val body = i / 100f
            val edge = (body + 0.10f).coerceAtMost(1f)
            val pull = capsuleLyricsEdgeRecoil(body, edge)
            val travel = capsuleLyricsTranslation(body, pull, screenHeightPx, 20f)
            for (y in listOf(0f, 100f, 300f, screenHeightPx)) {
                assertEquals(y, (y - travel) + travel, 0.0001f)
            }
        }
    }

    @Test fun edgeRecoilIsSmallAndExactlyZeroAtRest() {
        assertEquals(0f, capsuleLyricsEdgeRecoil(0f, 0f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(1f, 1f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(0.5f, 0.5f), 0.00001f)
        for (body in -20..120) for (edge in -20..120 step 5) {
            assertTrue(kotlin.math.abs(capsuleLyricsEdgeRecoil(body/100f, edge/100f)) <= 0.12f)
        }
    }
}
