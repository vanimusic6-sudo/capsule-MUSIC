package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.DockHandoverWindow
import com.nikhil.yt.ui.component.PlayerFoldWindow
import com.nikhil.yt.ui.component.PlayerTravelSheer
import com.nikhil.yt.ui.motion.CapsuleMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing the player is a handover, and the dock kept reading as late.
 *
 * Two things have to hold at once, and they pull against each other. The dock has to start coming up
 * early — that is the whole complaint — but the two surfaces must never both be partly transparent
 * at the same moment, or the wallpaper shows through the seam between a player that has faded and a
 * dock that has not arrived.
 *
 * Both follow from the dock's window being the wider one, and that is what is pinned here.
 */
class DockHandoverTest {
    /**
     * Mirrors the player layer, sheer included.
     *
     * The sheer is the one thing that could break the pair: it takes opacity away from the player
     * mid-travel, which is exactly where the two surfaces are handing over. Testing the fold alone
     * would test a formula the app no longer uses.
     */
    private fun playerAlpha(progress: Float): Float {
        val fold = CapsuleMotion.approach(progress, PlayerFoldWindow)
        val travelling = 4f * fold * (1f - fold)
        return fold * (1f - PlayerTravelSheer * travelling)
    }

    private fun dockAlpha(progress: Float) =
        1f - CapsuleMotion.approach(progress, DockHandoverWindow)

    @Test fun `the dock spans the whole close, so it cannot start any earlier`() {
        assertEquals(1f, DockHandoverWindow, 0f)
        assertTrue(
            "the dock must not hand over later than the player folds",
            DockHandoverWindow >= PlayerFoldWindow,
        )
    }

    @Test fun `the dock is already on its way up in the first part of the close`() {
        // progress runs 1 (open) down to 0 (docked). The old window left the dock at zero until the
        // player was three quarters of the way down, which is what read as it dropping in late.
        assertTrue("the dock is still absent at 80% open", dockAlpha(0.8f) > 0f)
        assertTrue("the dock is still absent at 90% open", dockAlpha(0.9f) > 0f)
    }

    @Test fun `the dock is invisible while the player is open`() {
        // Smoothstep has zero slope at the top, so a window this wide still costs nothing at rest
        // and cannot blink into view the instant the player is touched.
        assertEquals(0f, dockAlpha(1f), 0f)
        assertTrue("the dock is visible before the close starts", dockAlpha(0.98f) < 0.01f)
    }

    @Test fun `the pair is opaque at every point of the travel`() {
        (0..200).forEach { step ->
            val progress = step / 200f
            val total = playerAlpha(progress) + dockAlpha(progress)
            assertTrue(
                "at progress $progress the surfaces sum to $total, so the wallpaper shows through",
                total >= 1f - 1e-4f,
            )
        }
    }

    /**
     * The sheer exists to soften the journey, so it must leave both destinations alone: a player
     * sitting open or sitting docked is fully solid, and only what happens between them is touched.
     */
    @Test fun `the sheer is present in the middle and absent at both ends`() {
        assertEquals("an open player is not fully opaque", 1f, playerAlpha(1f), 1e-4f)
        assertEquals("a docked player is not fully gone", 0f, playerAlpha(0f), 1e-4f)

        val fold = CapsuleMotion.approach(0.38f, PlayerFoldWindow)
        assertTrue(
            "the sheer never applies, so it is decoration that does nothing",
            playerAlpha(0.38f) < fold,
        )
    }

    @Test fun `the sheer stays far too small to see the page through the player`() {
        (0..200).forEach { step ->
            val progress = step / 200f
            val fold = CapsuleMotion.approach(progress, PlayerFoldWindow)
            assertTrue(
                "at progress $progress the sheer removed more than a tenth of the player",
                playerAlpha(progress) >= fold * 0.9f,
            )
        }
    }

    @Test fun `both sides are exactly identity at the ends of the travel`() {
        assertEquals("the dock is solid once docked", 1f, dockAlpha(0f), 1e-4f)
        assertEquals("the player is gone once docked", 0f, playerAlpha(0f), 1e-4f)
        assertEquals("the player is solid when open", 1f, playerAlpha(1f), 0f)
    }
}
