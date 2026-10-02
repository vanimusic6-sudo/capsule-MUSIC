package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.expandedPlayerCanAcceptInput
import com.nikhil.yt.ui.component.PlayerExpandedInputFloor
import com.nikhil.yt.ui.component.shouldShowCompactSurface
import com.nikhil.yt.ui.component.shouldRenderExpandedSurface
import com.nikhil.yt.ui.component.SheetExpandedRenderFloor
import com.nikhil.yt.ui.component.EXPANDED_ANCHOR
import com.nikhil.yt.ui.component.DISMISSED_ANCHOR
import com.nikhil.yt.ui.component.COLLAPSED_ANCHOR
import com.nikhil.yt.ui.component.PlayerMorphHandoffWindow
import com.nikhil.yt.ui.component.playerMorphHandoff
import com.nikhil.yt.ui.component.playerFrameCornerRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the shared-surface player transition.
 *
 * Mini and full player ride the same BottomSheet. Only their opacity trades during the first part
 * of the travel, so there is no stretch, squash, extra descent or second trajectory to leave a
 * residue after an interrupted gesture.
 */
class DockHandoverTest {
    @Test fun `docked state belongs completely to the mini player`() {
        assertEquals(0f, playerMorphHandoff(0f), 0f)
    }

    @Test fun `full player has completely taken over after the handoff window`() {
        assertEquals(1f, playerMorphHandoff(PlayerMorphHandoffWindow), 0f)
        assertEquals(1f, playerMorphHandoff(1f), 0f)
    }

    @Test fun `handoff is monotonic and bounded`() {
        var previous = 0f
        for (step in 0..200) {
            val progress = step / 200f
            val handoff = playerMorphHandoff(progress)
            assertTrue("handoff escaped bounds at $progress: $handoff", handoff in 0f..1f)
            assertTrue("handoff reversed at $progress", handoff + 1e-6f >= previous)
            previous = handoff
        }
    }

    @Test fun `mini and full opacity are exact complements`() {
        for (step in 0..100) {
            val progress = step / 100f
            val full = playerMorphHandoff(progress)
            val mini = 1f - full
            assertEquals("opacity hole at $progress", 1f, mini + full, 1e-6f)
            assertTrue(mini in 0f..1f)
            assertTrue(full in 0f..1f)
        }
    }

    @Test fun `handoff completes early so the rest of travel is one full player`() {
        assertTrue(PlayerMorphHandoffWindow < 0.5f)
        assertEquals(1f, playerMorphHandoff(0.5f), 0f)
    }

    @Test fun `player frame corner can never become negative`() {
        val inputs = listOf(
            -1f,
            0f,
            0.9999f,
            0.99999994f,
            1f,
            1.0001f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
        )
        inputs.forEach { progress ->
            val radius = playerFrameCornerRadius(progress)
            assertTrue("negative corner for $progress: $radius", radius.value >= 0f)
        }
    }

    @Test fun `closing unmounts the full hit surface before microscopic spring residue can trap taps`() {
        assertTrue(!shouldRenderExpandedSurface(0f, EXPANDED_ANCHOR))
        assertTrue(shouldRenderExpandedSurface(SheetExpandedRenderFloor * 2f, EXPANDED_ANCHOR))
        assertTrue(shouldRenderExpandedSurface(SheetExpandedRenderFloor * 2f, COLLAPSED_ANCHOR))
        assertTrue(!shouldRenderExpandedSurface(SheetExpandedRenderFloor * 0.5f, COLLAPSED_ANCHOR))
        assertTrue(!shouldRenderExpandedSurface(0f, COLLAPSED_ANCHOR))
    }

    @Test fun `compact surface returns on every collapse and never survives dismissal`() {
        assertTrue(shouldShowCompactSurface(0f, COLLAPSED_ANCHOR))
        assertTrue(shouldShowCompactSurface(PlayerMorphHandoffWindow * 0.5f, COLLAPSED_ANCHOR))
        assertTrue(!shouldShowCompactSurface(PlayerMorphHandoffWindow, COLLAPSED_ANCHOR))
        assertTrue(!shouldShowCompactSurface(0f, DISMISSED_ANCHOR))
        assertTrue(shouldShowCompactSurface(0f, EXPANDED_ANCHOR))
    }

    @Test fun `full player controls stay inert through the dock handoff floor`() {
        assertTrue(!expandedPlayerCanAcceptInput(0f, COLLAPSED_ANCHOR))
        assertTrue(!expandedPlayerCanAcceptInput(PlayerExpandedInputFloor, COLLAPSED_ANCHOR))
        assertTrue(expandedPlayerCanAcceptInput(PlayerExpandedInputFloor + 0.01f, COLLAPSED_ANCHOR))
        assertTrue(expandedPlayerCanAcceptInput(1f, EXPANDED_ANCHOR))
        assertTrue(!expandedPlayerCanAcceptInput(1f, DISMISSED_ANCHOR))
    }

    @Test fun `full surface unmounts while still visually imperceptible`() {
        assertTrue(SheetExpandedRenderFloor >= 0.02f)
        assertTrue(playerMorphHandoff(SheetExpandedRenderFloor) < 0.01f)
    }

    @Test fun `animation input outside its normal range stays finite and safe`() {
        for (progress in listOf(-0.4f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val value = playerMorphHandoff(progress)
            assertTrue("$progress produced $value", value.isFinite())
            assertTrue("$progress escaped bounds: $value", value in 0f..1f)
        }
    }
}
