package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.ANCHOR_EPSILON_DP
import com.nikhil.yt.ui.component.isAtSheetAnchor
import com.nikhil.yt.ui.component.constrainBottomSheetDragDelta
import com.nikhil.yt.ui.component.canStartMiniDismissGesture
import com.nikhil.yt.ui.component.canStartMiniGestureCoordinator
import com.nikhil.yt.ui.component.miniPlayerForegroundCanAcceptInput
import com.nikhil.yt.ui.component.MiniDismissStartProgressCeiling
import com.nikhil.yt.ui.component.MiniGestureCoordinatorProgressCeiling
import com.nikhil.yt.ui.component.MiniOpenCommitProgress
import com.nikhil.yt.ui.component.MiniOpenFlingVelocity
import com.nikhil.yt.ui.component.MiniOpenReverseVelocity
import com.nikhil.yt.ui.component.MiniSurfaceFadeStart
import com.nikhil.yt.ui.component.MiniSurfaceFadeEnd
import com.nikhil.yt.ui.component.fullPlayerRevealOffset
import com.nikhil.yt.ui.component.miniPlayerPinOffset
import com.nikhil.yt.ui.component.miniPlayerSurfaceAlpha
import com.nikhil.yt.ui.component.shouldExpandMiniGesture
import com.nikhil.yt.ui.component.COLLAPSED_ANCHOR
import com.nikhil.yt.ui.component.DISMISSED_ANCHOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for having to press Back twice to leave a screen.
 *
 * The player's BackHandler is armed on `!isCollapsed`, and that used to be exact equality against
 * the anchor. A sheet lands exactly on its anchor only when an animation finishes on it; an
 * interrupted settle — a drag caught mid-animation, bounds changing underneath — leaves it a
 * fraction of a dp short. That rest is invisible, but it left `isCollapsed` false for good, so the
 * next Back press ran collapseSoft() on an already-collapsed sheet and was swallowed.
 */
class BottomSheetAnchorTest {
    @Test fun `a rest a fraction short of an anchor counts as being on it`() {
        // The kind of rest an interrupted settle leaves behind: indistinguishable from the anchor.
        assertTrue(isAtSheetAnchor(63.98.dp, 64.dp))
        assertTrue(isAtSheetAnchor(64.02.dp, 64.dp))
        assertTrue(isAtSheetAnchor(64.dp, 64.dp))
    }

    @Test fun `a position anyone could see is not an anchor`() {
        // The tolerance must not swallow a real position, or the player would stop handling Back
        // while it is still visibly open.
        assertFalse(isAtSheetAnchor(64.5.dp, 64.dp))
        assertFalse(isAtSheetAnchor(72.dp, 64.dp))
        assertFalse(isAtSheetAnchor(300.dp, 64.dp))
        assertFalse(isAtSheetAnchor(0.dp, 64.dp))
    }

    @Test fun `the tolerance stays under a single pixel at every density`() {
        // 0.05dp is below one physical pixel even at ldpi, so nothing visible can be mistaken for
        // resting on an anchor.
        assertTrue("tolerance is $ANCHOR_EPSILON_DP dp", ANCHOR_EPSILON_DP <= 0.1f)
        assertTrue("tolerance is $ANCHOR_EPSILON_DP dp", ANCHOR_EPSILON_DP > 0f)
    }

    @Test fun `it works the same at every anchor and in both directions`() {
        // Dismissed, collapsed and expanded all go through this, and an interrupted settle can
        // stop on either side of the target.
        listOf(0.dp, 64.dp, 640.dp).forEach { anchor ->
            assertTrue(isAtSheetAnchor(anchor - 0.04.dp, anchor))
            assertTrue(isAtSheetAnchor(anchor + 0.04.dp, anchor))
            assertFalse(isAtSheetAnchor(anchor + 1.dp, anchor))
        }
    }
    @Test fun `swipe to clear can only begin from the physical mini player`() {
        assertTrue(canStartMiniDismissGesture(0f))
        assertTrue(canStartMiniDismissGesture(MiniDismissStartProgressCeiling))
        assertFalse(canStartMiniDismissGesture(MiniDismissStartProgressCeiling + 0.01f))
        assertFalse(canStartMiniDismissGesture(0.5f))
        assertFalse(canStartMiniDismissGesture(1f))
    }

    @Test fun `compact gesture ownership lasts while compact controls are visible`() {
        assertTrue(canStartMiniGestureCoordinator(0f))
        assertTrue(canStartMiniGestureCoordinator(MiniDismissStartProgressCeiling + 0.01f))
        assertTrue(canStartMiniGestureCoordinator(MiniGestureCoordinatorProgressCeiling))
        assertFalse(canStartMiniGestureCoordinator(MiniGestureCoordinatorProgressCeiling + 0.01f))
    }

    @Test fun `mini controls stay interactive throughout their visible handoff`() {
        assertTrue(miniPlayerForegroundCanAcceptInput(0f, COLLAPSED_ANCHOR))
        assertTrue(
            miniPlayerForegroundCanAcceptInput(
                MiniDismissStartProgressCeiling + 0.01f,
                COLLAPSED_ANCHOR,
            ),
        )
        assertTrue(
            miniPlayerForegroundCanAcceptInput(
                MiniGestureCoordinatorProgressCeiling,
                COLLAPSED_ANCHOR,
            ),
        )
        assertFalse(
            miniPlayerForegroundCanAcceptInput(
                MiniGestureCoordinatorProgressCeiling + 0.01f,
                COLLAPSED_ANCHOR,
            ),
        )
        assertFalse(miniPlayerForegroundCanAcceptInput(0f, DISMISSED_ANCHOR))
    }


    @Test fun `mini and full player use independent transition geometry`() {
        val collapsed = 80.dp
        // Mini counter-translation grows exactly with sheet travel, so its screen position stays
        // fixed. Full Player instead owns a separate reveal offset that falls to zero.
        assertEquals(0.dp, miniPlayerPinOffset(80.dp, collapsed))
        assertEquals(40.dp, miniPlayerPinOffset(120.dp, collapsed))
        assertEquals(80.dp, fullPlayerRevealOffset(collapsed, 0f))
        assertEquals(40.dp, fullPlayerRevealOffset(collapsed, 0.5f))
        assertEquals(0.dp, fullPlayerRevealOffset(collapsed, 1f))
    }

    @Test fun `mini surface visibly fades instead of riding into full player`() {
        assertEquals(1f, miniPlayerSurfaceAlpha(0f), 0f)
        assertEquals(1f, miniPlayerSurfaceAlpha(MiniSurfaceFadeStart), 0f)
        val middle = miniPlayerSurfaceAlpha((MiniSurfaceFadeStart + MiniSurfaceFadeEnd) / 2f)
        assertTrue(middle in 0.01f..0.99f)
        assertEquals(0f, miniPlayerSurfaceAlpha(MiniSurfaceFadeEnd), 0f)
        assertEquals(0f, miniPlayerSurfaceAlpha(1f), 0f)
    }

    @Test fun `short intentional upward mini drag commits open`() {
        assertFalse(shouldExpandMiniGesture(MiniOpenCommitProgress - 0.01f, 0f))
        assertTrue(shouldExpandMiniGesture(MiniOpenCommitProgress, 0f))
        assertTrue(shouldExpandMiniGesture(0.01f, MiniOpenFlingVelocity))
        assertFalse(shouldExpandMiniGesture(0.8f, MiniOpenReverseVelocity))
    }

    @Test fun `mini player drag cannot cross its dock when dismissal is disabled`() {
        assertEquals(0f, constrainBottomSheetDragDelta(64f, 64f, 50f, false), 0f)
        assertEquals(16f, constrainBottomSheetDragDelta(80f, 64f, 200f, false), 0f)
        assertEquals(-50f, constrainBottomSheetDragDelta(64f, 64f, -50f, false), 0f)
        assertEquals(50f, constrainBottomSheetDragDelta(64f, 64f, 50f, true), 0f)
    }

}
