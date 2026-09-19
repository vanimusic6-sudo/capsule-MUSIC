package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.ANCHOR_EPSILON_DP
import com.nikhil.yt.ui.component.isAtSheetAnchor
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
}
