package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.MiniHandoverDrop
import com.nikhil.yt.ui.component.PlayerDescentBeyondDock
import com.nikhil.yt.ui.component.PlayerFoldHeightInset
import com.nikhil.yt.ui.component.PlayerFoldWidthInset
import com.nikhil.yt.ui.component.PlayerFoldWindow
import com.nikhil.yt.ui.component.miniHandoverDrop
import com.nikhil.yt.ui.component.playerFoldTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full player hands off to the mini player.
 *
 * The full sheet contracts and loses a little light while descending. The mini-player itself never
 * deforms: it only dips down briefly and returns to its exact dock geometry. Everything is a pure
 * function of progress so opening is the exact reverse of closing and an interrupted gesture cannot
 * leave visual residue behind.
 */
class DockHandoverTest {
    @Test fun `an open player is exactly identity`() {
        val open = playerFoldTransform(1f)
        assertEquals(1f, open.scaleX, 0f)
        assertEquals(1f, open.scaleY, 0f)
        assertEquals(0f, open.descentInDockHeights, 0f)
        assertEquals(1f, open.alpha, 0f)
        assertEquals(0f, open.dimness, 0f)
    }

    @Test fun `a docked player ends at the intended contracted geometry`() {
        val docked = playerFoldTransform(0f)
        assertEquals(1f - PlayerFoldWidthInset, docked.scaleX, 1e-6f)
        assertEquals(1f - PlayerFoldHeightInset, docked.scaleY, 1e-6f)
        assertEquals(PlayerDescentBeyondDock, docked.descentInDockHeights, 1e-6f)
    }

    @Test fun `fold geometry stays bounded for the whole gesture`() {
        (0..200).forEach { step ->
            val progress = step / 200f
            val transform = playerFoldTransform(progress)
            assertTrue(
                "scaleX escaped its physical range at $progress: ${transform.scaleX}",
                transform.scaleX in (1f - PlayerFoldWidthInset)..1f,
            )
            assertTrue(
                "scaleY escaped its physical range at $progress: ${transform.scaleY}",
                transform.scaleY in (1f - PlayerFoldHeightInset)..1f,
            )
            assertTrue(
                "descent escaped its physical range at $progress: ${transform.descentInDockHeights}",
                transform.descentInDockHeights in 0f..PlayerDescentBeyondDock,
            )
        }
    }

    @Test fun `closing is monotonic with no reversal or wobble`() {
        var previousScaleX = 1f
        var previousScaleY = 1f
        var previousDescent = 0f

        // Closing runs from progress 1 -> 0.
        for (step in 199 downTo 0) {
            val progress = step / 200f
            val transform = playerFoldTransform(progress)
            assertTrue(
                "scaleX grew again while closing at $progress",
                transform.scaleX <= previousScaleX + 1e-6f,
            )
            assertTrue(
                "scaleY grew again while closing at $progress",
                transform.scaleY <= previousScaleY + 1e-6f,
            )
            assertTrue(
                "descent reversed while closing at $progress",
                transform.descentInDockHeights >= previousDescent - 1e-6f,
            )
            previousScaleX = transform.scaleX
            previousScaleY = transform.scaleY
            previousDescent = transform.descentInDockHeights
        }
    }

    @Test fun `mid handoff remains finite and physical`() {
        val transform = playerFoldTransform(0.5f)
        assertTrue(transform.scaleX.isFinite())
        assertTrue(transform.scaleY.isFinite())
        assertTrue(transform.descentInDockHeights.isFinite())
        assertTrue(transform.scaleX < 1f)
        assertTrue(transform.scaleY < 1f)
        assertTrue(transform.descentInDockHeights > 0f)
    }

    @Test
    fun `the player keeps its light while it is fully open`() {
        val open = playerFoldTransform(1f)

        assertEquals(1f, open.alpha, 0.001f)
        assertEquals("an open player must not be dimmed", 0f, open.dimness, 0.001f)
    }

    @Test
    fun `it darkens gently as it goes down`() {
        var previousAlpha = playerFoldTransform(1f).alpha
        var previousDim = playerFoldTransform(1f).dimness

        for (progress in listOf(0.8f, 0.6f, 0.4f, 0.2f, 0f)) {
            val fold = playerFoldTransform(progress)
            assertTrue("alpha rose at $progress", fold.alpha <= previousAlpha + 0.001f)
            assertTrue("darkening fell at $progress", fold.dimness >= previousDim - 0.001f)
            previousAlpha = fold.alpha
            previousDim = fold.dimness
        }
    }

    @Test
    fun `nothing of the player is left at the dock`() {
        val docked = playerFoldTransform(0f)

        assertEquals("it is still there", 0f, docked.alpha, 0.0001f)
        assertTrue("darkening must stay subtle", docked.dimness < 0.25f)
    }

    @Test
    fun `the player dissolves late rather than evenly`() {
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
        assertEquals("docked", 0f, miniHandoverDrop(0f), 0.0001f)
        assertEquals("open", 0f, miniHandoverDrop(1f), 0.0001f)
    }

    @Test
    fun `the mini player dips without changing its geometry`() {
        val deepest = (0..100).maxOf { miniHandoverDrop(it / 100f) }

        assertTrue("it never yielded", deepest > 0f)
        assertTrue("it yielded far too much", deepest <= MiniHandoverDrop + 0.0001f)
        for (step in 0..100) {
            val drop = miniHandoverDrop(step / 100f)
            assertTrue("the mini player moved upward at $step", drop >= 0f)
        }
    }

    @Test
    fun `the handover survives values the animation can hand it`() {
        for (progress in listOf(-0.4f, 1.6f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val drop = miniHandoverDrop(progress)
            assertTrue("$progress produced $drop", drop.isFinite() && drop >= 0f)
            val fold = playerFoldTransform(progress)
            assertTrue("$progress alpha ${fold.alpha}", fold.alpha in 0f..1f)
            assertTrue("$progress dimness ${fold.dimness}", fold.dimness in 0f..1f)
        }
    }

    @Test
    fun `going out starts before folding does`() {
        val atFoldStart = playerFoldTransform(PlayerFoldWindow)

        assertEquals("the width fold has not begun here", 1f, atFoldStart.scaleX, 0.001f)
        assertEquals("the height fold has not begun here", 1f, atFoldStart.scaleY, 0.001f)
        assertTrue("but the player is already going", atFoldStart.dimness > 0f)
    }
}
