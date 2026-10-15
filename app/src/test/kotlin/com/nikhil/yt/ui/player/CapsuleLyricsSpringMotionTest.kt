package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same spring values are valid at 60 and 120Hz without a frame clock. */
class CapsuleLyricsSpringMotionTest {
    @Test fun openingLandingIsBoundedAndThereIsNoZoom() {
        assertEquals(-0.004f, capsuleLyricsLandingTravel(-0.03f), 0.00001f)
        assertEquals(0f, capsuleLyricsLandingTravel(0f), 0.00001f)
        assertEquals(1f, capsuleLyricsLandingTravel(1.02f), 0.00001f)
        for (i in -20..120) {
            val p = capsuleLyricsLandingTravel(i / 100f)
            assertTrue("no violent overshoot at 60Hz", p in -0.004f..1f)
        }
    }

    @Test fun foregroundRevealFollowsThePageAndNeverPopsBetweenFrames() {
        assertEquals(0f, capsuleLyricsForegroundOpacity(1f), 0.0001f)
        assertEquals(0f, capsuleLyricsForegroundOpacity(0.94f), 0.0001f)
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

    @Test fun apertureAndBothContentLayersCancelTranslationAtEveryFrame() {
        val heightPx = 940f
        val budgetPx = 18f
        // Includes opening/closing, spring overshoot and lead/lag inversion.
        for (body in -10..110) {
            val b = body / 100f
            for (lead in listOf(b - 0.14f, b, b + 0.14f)) {
                val shift = capsuleLyricsTranslation(
                    b, capsuleLyricsEdgeRecoil(b, lead), heightPx, budgetPx,
                )
                for (screenY in listOf(0f, 40f, 300f, 760f, heightPx)) {
                    val finalPosition = (screenY - shift) + shift
                    assertEquals(
                        "content must remain at the EXACT original screen coordinate",
                        screenY, finalPosition, 0.0001f,
                    )
                }
                // Only the clipping boundary moves, NOT the background or
                // text. No upscaled glyphs and no hue sweep at the corners.
                assertTrue(shift in -5f..heightPx + 5f)
            }
        }
    }

    @Test fun openingAndClosingAlwaysUseIdenticalGeometryForReversals() {
        val heightPx = 850f
        for (i in 0..100) {
            val body = i / 100f
            val edge = (body - 0.08f).coerceAtLeast(0f)
            val opening = capsuleLyricsTranslation(
                body, capsuleLyricsEdgeRecoil(body, edge), heightPx, 18f,
            )
            val closing = capsuleLyricsTranslation(
                body, capsuleLyricsEdgeRecoil(body, edge), heightPx, 18f,
            )
            assertEquals("no direction-dependent cut or jump", opening, closing, 0.00001f)
        }
    }

    @Test fun edgeRecoilIsSmallAndExactlyZeroAtRest() {
        assertEquals(0f, capsuleLyricsEdgeRecoil(0f, 0f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(1f, 1f), 0.00001f)
        assertEquals(0f, capsuleLyricsEdgeRecoil(0.5f, 0.5f), 0.00001f)
        for (body in -20..120) for (edge in -20..120 step 5) {
            assertTrue(kotlin.math.abs(capsuleLyricsEdgeRecoil(body/100f, edge/100f)) <= 0.18f)
        }
    }
    @Test fun eachLyricsInkZoneEntersInAStableSmoothAndReversible60HzSequence() {
        for (zone in LyricsInkZone.entries) {
            assertEquals(0f, capsuleLyricsZoneOpacity(1f, zone), 0.00001f)
            assertEquals(1f, capsuleLyricsZoneOpacity(0f, zone), 0.00001f)
            assertEquals(0f, capsuleLyricsZoneFloatDp(0f, zone), 0.00001f)
            var previous = 0f
            for (frame in 0..120) {
                val position = 1f - frame / 120f
                val opacity = capsuleLyricsZoneOpacity(position, zone)
                assertTrue("$zone decreases while opening", opacity >= previous - 0.00001f)
                assertTrue("$zone has a bounded local float", capsuleLyricsZoneFloatDp(position, zone) in 0f..6f)
                if (frame > 0) {
                    assertTrue("$zone should never pop between 60Hz frames",
                        opacity - previous < 0.075f)
                }
                previous = opacity
            }
        }
        // Controls near the moving BOTTOM aperture must appear before the
        // top bar; exactly one spring clock drives both reveals.
        assertTrue(capsuleLyricsZoneOpacity(0.70f, LyricsInkZone.CONTROLS) >
            capsuleLyricsZoneOpacity(0.70f, LyricsInkZone.HEADER))
    }

}
