package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.MiniPlayerForegroundFadeEnd
import com.nikhil.yt.ui.component.MiniPlayerForegroundFadeStart
import com.nikhil.yt.ui.component.MiniSurfaceFadeEnd
import com.nikhil.yt.ui.component.MiniSurfaceFadeStart
import com.nikhil.yt.ui.component.PlayerContentHandoffStart
import com.nikhil.yt.ui.component.PlayerExpandedInputFloor
import com.nikhil.yt.ui.component.SheetExpandedRenderFloor
import com.nikhil.yt.ui.component.expandedPlayerCanAcceptInput
import com.nikhil.yt.ui.component.fullPlayerForegroundAlpha
import com.nikhil.yt.ui.component.miniPlayerForegroundAlpha
import com.nikhil.yt.ui.component.miniPlayerForegroundCanAcceptInput
import com.nikhil.yt.ui.component.miniPlayerPinOffset
import com.nikhil.yt.ui.component.miniPlayerSurfaceAlpha
import com.nikhil.yt.ui.component.playerFrameCornerRadius
import com.nikhil.yt.ui.component.shouldRenderExpandedSurface
import com.nikhil.yt.ui.component.shouldShowCompactSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mini and full Player are separate content trees that render from one physical transition
 * progress. No assertion here depends on requested direction or a second transition clock.
 */
class DockHandoverTest {
    @Test fun `mini stays pinned while the full sheet rises from the same leading edge`() {
        val expanded = 800.dp
        val collapsed = 80.dp
        val dockTop = expanded - collapsed

        for (step in 0..20) {
            val progress = step / 20f
            val value = collapsed + (expanded - collapsed) * progress
            val sheetTop = expanded - value
            val miniTop = sheetTop + miniPlayerPinOffset(value, collapsed)

            assertEquals("mini moved at progress=$progress", dockTop.value, miniTop.value, 0.001f)
            assertTrue("full sheet crossed below mini at $progress", sheetTop <= miniTop)
        }
    }

    @Test fun `mini shell and full foreground form one complementary handoff`() {
        for (step in 0..100) {
            val progress = step / 100f
            val mini = miniPlayerSurfaceAlpha(progress)
            val full = fullPlayerForegroundAlpha(progress)
            assertTrue(mini in 0f..1f)
            assertTrue(full in 0f..1f)
            assertEquals("handoff gap at $progress", 1f, mini + full, 0.0001f)
        }
        assertEquals(1f, miniPlayerSurfaceAlpha(MiniSurfaceFadeStart), 0f)
        assertEquals(0f, fullPlayerForegroundAlpha(PlayerContentHandoffStart), 0f)
        assertEquals(0f, miniPlayerSurfaceAlpha(MiniSurfaceFadeEnd), 0f)
        assertEquals(1f, fullPlayerForegroundAlpha(MiniSurfaceFadeEnd), 0f)
    }

    @Test fun `full player is composed before it becomes visually responsible`() {
        val precomposeProgress = SheetExpandedRenderFloor * 2f
        assertTrue(shouldRenderExpandedSurface(precomposeProgress, isDismissed = false))
        assertTrue(precomposeProgress < PlayerContentHandoffStart)
        assertEquals(0f, fullPlayerForegroundAlpha(precomposeProgress), 0f)
        assertFalse(shouldRenderExpandedSurface(0f, isDismissed = false))
        assertFalse(shouldRenderExpandedSurface(1f, isDismissed = true))
    }

    @Test fun `mini controls release before the shell and input follows physical progress`() {
        assertEquals(1f, miniPlayerForegroundAlpha(MiniPlayerForegroundFadeStart), 0f)
        assertEquals(0f, miniPlayerForegroundAlpha(MiniPlayerForegroundFadeEnd), 0f)
        assertTrue(miniPlayerSurfaceAlpha(MiniPlayerForegroundFadeEnd) > 0f)
        assertTrue(miniPlayerForegroundCanAcceptInput(0f, isDismissed = false))
        assertTrue(miniPlayerForegroundCanAcceptInput(MiniPlayerForegroundFadeEnd, isDismissed = false))
        assertFalse(
            miniPlayerForegroundCanAcceptInput(
                MiniPlayerForegroundFadeEnd + 0.01f,
                isDismissed = false,
            ),
        )
        assertFalse(miniPlayerForegroundCanAcceptInput(0f, isDismissed = true))
    }

    @Test fun `full controls do not overlap mini control ownership`() {
        assertFalse(expandedPlayerCanAcceptInput(0f, isDismissed = false))
        assertFalse(expandedPlayerCanAcceptInput(PlayerExpandedInputFloor - 0.01f, isDismissed = false))
        assertTrue(expandedPlayerCanAcceptInput(PlayerExpandedInputFloor, isDismissed = false))
        assertFalse(expandedPlayerCanAcceptInput(1f, isDismissed = true))
        assertTrue(PlayerExpandedInputFloor > MiniPlayerForegroundFadeEnd)
    }

    @Test fun `compact composition follows physical visibility not requested target`() {
        assertTrue(shouldShowCompactSurface(0f, isDismissed = false))
        assertTrue(shouldShowCompactSurface(MiniSurfaceFadeEnd * 0.5f, isDismissed = false))
        assertFalse(shouldShowCompactSurface(MiniSurfaceFadeEnd, isDismissed = false))
        assertFalse(shouldShowCompactSurface(0f, isDismissed = true))
    }

    @Test fun `transition functions stay monotonic bounded and corner safe`() {
        var previousMini = 1f
        var previousFull = 0f
        for (step in 0..200) {
            val progress = step / 200f
            val mini = miniPlayerSurfaceAlpha(progress)
            val full = fullPlayerForegroundAlpha(progress)
            assertTrue(mini <= previousMini + 1e-6f)
            assertTrue(full + 1e-6f >= previousFull)
            previousMini = mini
            previousFull = full
        }

        listOf(-1f, 0f, 0.9999f, 1f, 1.0001f, Float.NaN, Float.POSITIVE_INFINITY)
            .forEach { progress ->
                assertTrue(playerFrameCornerRadius(progress).value >= 0f)
                assertTrue(miniPlayerSurfaceAlpha(progress).isFinite())
                assertTrue(fullPlayerForegroundAlpha(progress).isFinite())
            }
    }
}
