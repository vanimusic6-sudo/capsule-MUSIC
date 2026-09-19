package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.PlayerDescentBeyondDock
import com.nikhil.yt.ui.component.PlayerFoldScale
import com.nikhil.yt.ui.component.PlayerFoldWindow
import com.nikhil.yt.ui.component.playerFoldTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full player hands off to the mini player using geometry only.
 *
 * The mini player is already mounted behind the full player, so the foreground can stay fully
 * opaque while it shrinks and travels down into the dock. Keeping opacity out of this contract is
 * important: a screen-sized alpha blend is both more expensive and exactly the visual treatment the
 * Capsule motion system avoids.
 */
class DockHandoverTest {
    @Test fun `an open player is exactly identity`() {
        val open = playerFoldTransform(1f)
        assertEquals(1f, open.scale, 0f)
        assertEquals(0f, open.descentInDockHeights, 0f)
    }

    @Test fun `a docked player ends at the intended folded geometry`() {
        val docked = playerFoldTransform(0f)
        assertEquals(1f - PlayerFoldScale, docked.scale, 1e-6f)
        assertEquals(PlayerDescentBeyondDock, docked.descentInDockHeights, 1e-6f)
    }

    @Test fun `fold geometry stays bounded for the whole gesture`() {
        (0..200).forEach { step ->
            val progress = step / 200f
            val transform = playerFoldTransform(progress)
            assertTrue(
                "scale escaped its physical range at $progress: ${transform.scale}",
                transform.scale in (1f - PlayerFoldScale)..1f,
            )
            assertTrue(
                "descent escaped its physical range at $progress: ${transform.descentInDockHeights}",
                transform.descentInDockHeights in 0f..PlayerDescentBeyondDock,
            )
        }
    }

    @Test fun `closing is monotonic with no reversal or wobble`() {
        var previousScale = 1f
        var previousDescent = 0f

        // Closing runs from progress 1 -> 0.
        for (step in 199 downTo 0) {
            val progress = step / 200f
            val transform = playerFoldTransform(progress)
            assertTrue(
                "scale grew again while closing at $progress",
                transform.scale <= previousScale + 1e-6f,
            )
            assertTrue(
                "descent reversed while closing at $progress",
                transform.descentInDockHeights >= previousDescent - 1e-6f,
            )
            previousScale = transform.scale
            previousDescent = transform.descentInDockHeights
        }
    }

    @Test fun `mid handoff remains finite and physical`() {
        val transform = playerFoldTransform(0.5f)
        assertTrue(transform.scale.isFinite())
        assertTrue(transform.descentInDockHeights.isFinite())
        assertTrue(transform.scale < 1f)
        assertTrue(transform.descentInDockHeights > 0f)
    }

    @Test
    fun `the player is whole while it is still open`() {
        val open = playerFoldTransform(1f)

        assertEquals(1f, open.alpha, 0.001f)
        assertEquals("nothing drains from a player nobody is closing", 0f, open.greyness, 0.001f)
    }

    @Test
    fun `it goes out as it goes down`() {
        var previousAlpha = playerFoldTransform(1f).alpha
        var previousGrey = playerFoldTransform(1f).greyness

        for (progress in listOf(0.8f, 0.6f, 0.4f, 0.2f, 0f)) {
            val fold = playerFoldTransform(progress)
            assertTrue("alpha rose at $progress", fold.alpha <= previousAlpha + 0.001f)
            assertTrue("grey fell at $progress", fold.greyness >= previousGrey - 0.001f)
            previousAlpha = fold.alpha
            previousGrey = fold.greyness
        }
    }

    @Test
    fun `something is still there when it reaches the dock`() {
        // Fading to nothing would leave the last of the descent happening behind an empty
        // rectangle, and the motion loses the thing it is about.
        val docked = playerFoldTransform(0f)

        assertTrue("it vanished entirely", docked.alpha > 0f)
        assertTrue("it is still a picture, not a grey slab", docked.greyness < 1f)
    }

    @Test
    fun `going out starts before folding does`() {
        // The fold is about arriving at the dock; going out is about leaving, and leaving has to
        // start earlier or it reads as a blink at the end.
        val atFoldStart = playerFoldTransform(PlayerFoldWindow)

        assertEquals("the fold has not begun here", 1f, atFoldStart.scale, 0.001f)
        assertTrue("but the player is already going", atFoldStart.greyness > 0f)
    }
}
