package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.PlayerDescentBeyondDock
import com.nikhil.yt.ui.component.PlayerFoldScale
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

    @Test fun `the handoff contract contains geometry only`() {
        val transform = playerFoldTransform(0.5f)
        assertTrue(transform.scale.isFinite())
        assertTrue(transform.descentInDockHeights.isFinite())
        // The type intentionally exposes only geometry; adding opacity would require changing this
        // compile-time contract and this test file alongside it rather than sneaking a fade back in.
        assertEquals(2, transform::class.java.declaredFields.count { !it.isSynthetic })
    }
}
