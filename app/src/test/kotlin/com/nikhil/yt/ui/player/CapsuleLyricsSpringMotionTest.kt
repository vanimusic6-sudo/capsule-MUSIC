package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLyricsSpringMotionTest {
    @Test fun retreatIsPerceptibleDuringTravelButZeroAtBothRestPositions() {
        assertEquals(0f, capsuleLyricsRetreat(0f, 0f), 0.00001f)
        assertEquals(0f, capsuleLyricsRetreat(1f, 1f), 0.00001f)
        val centre = capsuleLyricsRetreat(0.5f, 0.5f)
        assertTrue("retreat at half-flight should visibly exceed 3%", centre > 0.03f)
        for (body in -15..115) {
            for (lead in -15..115 step 5) {
                val depth = capsuleLyricsRetreat(body / 100f, lead / 100f)
                assertTrue("no GPU overscale/underscale even when spring overshoots",
                    depth in 0f..0.043f)
            }
        }
    }

    @Test fun foregroundNeverWaitsForAnUnrelatedDelayOrPopsAtRest() {
        assertEquals(0f, capsuleLyricsForegroundOpacity(1f), 0.0001f)
        assertEquals(0f, capsuleLyricsForegroundOpacity(0.95f), 0.0001f)
        assertEquals(1f, capsuleLyricsForegroundOpacity(0f), 0.0001f)
        assertEquals(1f, capsuleLyricsForegroundOpacity(0.2f), 0.0001f)
        var previous = 0f
        for (frame in 0..120) {
            // Represent fixed 60Hz capture positions from hidden to visible.
            val p = 1f - frame / 120f
            val a = capsuleLyricsForegroundOpacity(p)
            assertTrue("optical visibility must be continuous and nondecreasing", a >= previous - 0.000001f)
            if (frame < 120) {
                val next = capsuleLyricsForegroundOpacity(1f - (frame + 1) / 120f)
                assertTrue("no one-frame opacity jump at 60Hz", kotlin.math.abs(next-a) < 0.04f)
            }
            previous = a
        }
    }

    @Test fun backdropInverseScaleDoesNotChangeItsRealScreenGeometry() {
        // Outer layer: pivot at bottom, scale, then translate.
        // Inner backdrop: reverse transform, same pivot.
        val height = 850f
        for (tick in 0..100) {
            val body = tick / 100f
            val lead = (body + 0.12f).coerceAtMost(1f)
            val recoil = capsuleLyricsEdgeRecoil(body, lead)
            val retreat = capsuleLyricsRetreat(body, lead)
            val travel = capsuleLyricsTranslation(body, recoil, height, 40f)
            val outerScale = 1f - retreat * 0.62f
            val inverseScale = 1f / outerScale
            for (sourceY in listOf(0f, 160f, 450f, height)) {
                val afterInner = height + (sourceY - height) * inverseScale -
                    travel / outerScale
                val afterOuter = height + (afterInner - height) * outerScale + travel
                assertEquals("fixed gradient may not wander during spring motion",
                    sourceY, afterOuter, 0.001f)
            }
        }
    }

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
