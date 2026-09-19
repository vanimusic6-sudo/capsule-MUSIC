package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.PlayerDescentBeyondDock
import com.nikhil.yt.ui.component.PlayerFoldScale
import com.nikhil.yt.ui.component.MiniHandoverStretch
import com.nikhil.yt.ui.component.PlayerFoldWindow
import com.nikhil.yt.ui.component.miniHandoverStretch
import com.nikhil.yt.ui.component.playerFoldTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full player hands off to the mini player.
 *
 * Geometry does most of it — the mini player is already mounted behind, so shrinking and
 * descending reveals it for free — but the player also goes out on the way down, dissolving and
 * draining of colour until nothing of it is left at the dock. That opacity is the one exception to
 * the Capsule motion system's no-transparency rule and is bounded by it: it belongs to a gesture
 * that ends, never to a surface that sits.
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
    fun `nothing of the player is left at the dock`() {
        // Anything still drawn here is drawn over the mini player that has just arrived, which is
        // what read as a sheet that never quite left.
        val docked = playerFoldTransform(0f)

        assertEquals("it is still there", 0f, docked.alpha, 0.0001f)
        assertTrue("it is still a picture, not a grey slab", docked.greyness < 1f)
    }

    @Test
    fun `the player dissolves late rather than evenly`() {
        // The complaint a straight ramp earns is that the player is half-gone in the middle of
        // the travel and still faintly there at the end: never solid, never actually away. It has
        // to be held up through the middle and let go of near the dock.
        assertTrue(
            "it had already faded by mid-travel",
            playerFoldTransform(0.5f).alpha > 0.5f,
        )
        assertTrue(
            "it was still visible just above the dock",
            playerFoldTransform(0.1f).alpha < 0.1f,
        )
    }

    @Test
    fun `a resting mini player carries no residue of the gesture`() {
        // The whole reason this is a pure function of progress: a scale left behind at either
        // anchor is a permanently stretched dock, not an animation.
        assertEquals("docked", 1f, miniHandoverStretch(0f), 0.0001f)
        assertEquals("open", 1f, miniHandoverStretch(1f), 0.0001f)
    }

    @Test
    fun `the mini player gives downward as the player lands on it`() {
        val deepest = (0..100).maxOf { miniHandoverStretch(it / 100f) }

        assertTrue("it never yielded", deepest > 1f)
        // Weight, not a bounce: a dock that visibly leaps is a different animation.
        assertTrue("it yielded far too much", deepest < 1f + 2f * MiniHandoverStretch)
        for (step in 0..100) {
            val stretch = miniHandoverStretch(step / 100f)
            assertTrue("the mini player shrank at $step", stretch >= 1f)
        }
    }

    @Test
    fun `the handover survives values the animation can hand it`() {
        // progress arrives from an Animatable mid-flight and has overshot its bounds before.
        for (progress in listOf(-0.4f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val stretch = miniHandoverStretch(progress)
            assertTrue("$progress produced $stretch", stretch.isFinite() && stretch >= 1f)
            val fold = playerFoldTransform(progress)
            assertTrue("$progress alpha ${fold.alpha}", fold.alpha in 0f..1f)
            assertTrue("$progress grey ${fold.greyness}", fold.greyness in 0f..1f)
        }
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
