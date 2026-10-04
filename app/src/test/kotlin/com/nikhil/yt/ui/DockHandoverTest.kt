package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.COLLAPSED_ANCHOR
import com.nikhil.yt.ui.component.DISMISSED_ANCHOR
import com.nikhil.yt.ui.component.EXPANDED_ANCHOR
import com.nikhil.yt.ui.component.FullPlayerCloseForegroundFadeEnd
import com.nikhil.yt.ui.component.FullPlayerCloseForegroundFadeStart
import com.nikhil.yt.ui.component.MiniPlayerForegroundFadeEnd
import com.nikhil.yt.ui.component.MiniPlayerForegroundFadeStart
import com.nikhil.yt.ui.component.MiniSurfaceFadeEnd
import com.nikhil.yt.ui.component.MiniSurfaceFadeStart
import com.nikhil.yt.ui.component.PlayerExpandedInputFloor
import com.nikhil.yt.ui.component.SheetExpandedRenderFloor
import com.nikhil.yt.ui.component.expandedPlayerCanAcceptInput
import com.nikhil.yt.ui.component.fullPlayerForegroundAlpha
import com.nikhil.yt.ui.component.fullPlayerRevealOffset
import com.nikhil.yt.ui.component.miniPlayerForegroundAlpha
import com.nikhil.yt.ui.component.miniPlayerForegroundCanAcceptInput
import com.nikhil.yt.ui.component.miniPlayerPinOffset
import com.nikhil.yt.ui.component.miniPlayerSurfaceAlpha
import com.nikhil.yt.ui.component.playerFrameCornerRadius
import com.nikhil.yt.ui.component.shouldRenderExpandedSurface
import com.nikhil.yt.ui.component.shouldShowCompactSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the independent Mini Player <-> Player transition.
 *
 * Mini Player stays pinned to the dock. Full Player is a separate canvas that travels from below
 * the viewport. They share only the scalar open progress; neither surface is the other's layout.
 */
class DockHandoverTest {
    @Test fun `mini stays pinned while full player travels independently`() {
        val expanded = 800.dp
        val collapsed = 80.dp

        for (step in 0..20) {
            val progress = step / 20f
            val value = collapsed + (expanded - collapsed) * progress
            val parentOffset = expanded - value

            val miniScreenTop =
                parentOffset + miniPlayerPinOffset(value, collapsed)
            assertEquals(
                "mini moved at progress=$progress",
                (expanded - collapsed).value,
                miniScreenTop.value,
                0.001f,
            )

            val fullScreenTop =
                parentOffset + fullPlayerRevealOffset(collapsed, progress)
            assertEquals(
                "full player did not own its own trajectory at progress=$progress",
                (expanded * (1f - progress)).value,
                fullScreenTop.value,
                0.001f,
            )
        }
    }

    @Test fun `dismiss drag does not counter pin mini below its dock`() {
        assertEquals(0.dp, miniPlayerPinOffset(80.dp, 80.dp))
        assertEquals(20.dp, miniPlayerPinOffset(100.dp, 80.dp))
        assertEquals(0.dp, miniPlayerPinOffset(40.dp, 80.dp))
    }

    @Test fun `mini surface fades on its own timeline`() {
        assertEquals(1f, miniPlayerSurfaceAlpha(0f), 0f)
        assertEquals(1f, miniPlayerSurfaceAlpha(MiniSurfaceFadeStart), 0f)
        assertEquals(0f, miniPlayerSurfaceAlpha(MiniSurfaceFadeEnd), 0f)
        assertEquals(0f, miniPlayerSurfaceAlpha(1f), 0f)

        var previous = 1f
        for (step in 0..200) {
            val progress = step / 200f
            val alpha = miniPlayerSurfaceAlpha(progress)
            assertTrue(alpha in 0f..1f)
            assertTrue("mini surface became brighter at $progress", alpha <= previous + 1e-6f)
            previous = alpha
        }
    }

    @Test fun `compact lifecycle follows mini fade rather than full player geometry`() {
        assertTrue(shouldShowCompactSurface(0f, COLLAPSED_ANCHOR))
        assertTrue(shouldShowCompactSurface(MiniSurfaceFadeEnd * 0.5f, COLLAPSED_ANCHOR))
        assertTrue(!shouldShowCompactSurface(MiniSurfaceFadeEnd, COLLAPSED_ANCHOR))
        assertTrue(!shouldShowCompactSurface(0f, DISMISSED_ANCHOR))
        assertTrue(shouldShowCompactSurface(0f, EXPANDED_ANCHOR))
    }

    @Test fun `mini controls dissolve before the independent mini surface is gone`() {
        assertEquals(1f, miniPlayerForegroundAlpha(0f), 0f)
        assertEquals(1f, miniPlayerForegroundAlpha(MiniPlayerForegroundFadeStart), 0f)
        assertEquals(0f, miniPlayerForegroundAlpha(MiniPlayerForegroundFadeEnd), 0f)
        assertTrue(miniPlayerSurfaceAlpha(MiniPlayerForegroundFadeEnd) > 0f)
    }

    @Test fun `mini foreground fade is monotonic and bounded`() {
        var previousMini = 1f
        for (step in 0..200) {
            val progress = step / 200f
            val mini = miniPlayerForegroundAlpha(progress)
            assertTrue(mini in 0f..1f)
            assertTrue("mini foreground rose while opening at $progress", mini <= previousMini + 1e-6f)
            previousMini = mini
        }
    }

    @Test fun `full foreground arrives with the independent full canvas on opening`() {
        for (progress in listOf(0f, 0.05f, 0.2f, 0.5f, 1f)) {
            assertEquals(
                "opening foreground detached at $progress",
                1f,
                fullPlayerForegroundAlpha(progress, EXPANDED_ANCHOR),
                0f,
            )
        }
    }

    @Test fun `full foreground releases only near the end of closing`() {
        assertEquals(
            1f,
            fullPlayerForegroundAlpha(FullPlayerCloseForegroundFadeStart, COLLAPSED_ANCHOR),
            0f,
        )
        assertEquals(
            0f,
            fullPlayerForegroundAlpha(FullPlayerCloseForegroundFadeEnd, COLLAPSED_ANCHOR),
            0f,
        )
        assertEquals(1f, fullPlayerForegroundAlpha(0.5f, COLLAPSED_ANCHOR), 0f)
    }

    @Test fun `docked mini controls remain alive when an opening drag declares its target`() {
        assertTrue(miniPlayerForegroundCanAcceptInput(0f, COLLAPSED_ANCHOR))
        assertTrue(miniPlayerForegroundCanAcceptInput(0f, EXPANDED_ANCHOR))
        assertTrue(
            !miniPlayerForegroundCanAcceptInput(
                MiniPlayerForegroundFadeEnd + 0.01f,
                EXPANDED_ANCHOR,
            ),
        )
        assertTrue(!miniPlayerForegroundCanAcceptInput(0f, DISMISSED_ANCHOR))
    }

    @Test fun `full player controls stay inert through the dock floor`() {
        assertTrue(!expandedPlayerCanAcceptInput(0f, COLLAPSED_ANCHOR))
        assertTrue(!expandedPlayerCanAcceptInput(PlayerExpandedInputFloor, COLLAPSED_ANCHOR))
        assertTrue(expandedPlayerCanAcceptInput(PlayerExpandedInputFloor + 0.01f, COLLAPSED_ANCHOR))
        assertTrue(expandedPlayerCanAcceptInput(1f, EXPANDED_ANCHOR))
        assertTrue(!expandedPlayerCanAcceptInput(1f, DISMISSED_ANCHOR))
    }

    @Test fun `full hit surface mounts only after real travel begins`() {
        assertTrue(!shouldRenderExpandedSurface(0f, EXPANDED_ANCHOR))
        assertTrue(shouldRenderExpandedSurface(SheetExpandedRenderFloor * 2f, EXPANDED_ANCHOR))
        assertTrue(shouldRenderExpandedSurface(SheetExpandedRenderFloor * 2f, COLLAPSED_ANCHOR))
        assertTrue(!shouldRenderExpandedSurface(SheetExpandedRenderFloor * 0.5f, COLLAPSED_ANCHOR))
        assertTrue(!shouldRenderExpandedSurface(0f, COLLAPSED_ANCHOR))
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

    @Test fun `independent transition functions survive hostile input`() {
        for (progress in listOf(-0.4f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val alpha = miniPlayerSurfaceAlpha(progress)
            val reveal = fullPlayerRevealOffset(80.dp, progress)
            assertTrue("$progress produced alpha=$alpha", alpha.isFinite())
            assertTrue("$progress escaped alpha bounds: $alpha", alpha in 0f..1f)
            assertTrue("$progress produced reveal=$reveal", reveal.value.isFinite())
            assertTrue("$progress produced negative reveal=$reveal", reveal >= 0.dp)
        }
    }
}
