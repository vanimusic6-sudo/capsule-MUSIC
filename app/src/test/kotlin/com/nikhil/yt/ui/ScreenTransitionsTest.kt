package com.nikhil.yt.ui

import com.nikhil.yt.ui.screens.ScreenTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route transitions must make a caught overlap impossible, not merely unlikely.
 *
 * Two destinations showing through each other on a Back press was the visible symptom; the cause is
 * that `AnimatedContent` composes both at once. The guarantee here is arithmetic: every enter waits
 * out the whole exit, so the outgoing destination has reached zero opacity before the incoming one
 * draws its first pixel.
 */
class ScreenTransitionsTest {
    @Test fun `an enter never begins before its exit has finished`() {
        // The guarantee is a property of the timings themselves, so assert them directly: the
        // delay in front of every enter is the entire life of the exit it follows.
        assertTrue(
            "an enter would start while the previous screen is still visible",
            ScreenTransitions.EnterDelayMillis >= ScreenTransitions.ExitMillis,
        )
        assertTrue("the exit must actually take time", ScreenTransitions.ExitMillis > 0)
        assertTrue("the enter must actually take time", ScreenTransitions.EnterMillis > 0)
    }

    @Test fun `a route change stays brisk end to end`() {
        // Sequencing costs the sum of both halves, so it has to stay well inside the range where a
        // navigation still feels immediate.
        val total = ScreenTransitions.ExitMillis + ScreenTransitions.EnterMillis
        assertTrue("a route change takes ${total}ms", total in 150..340)
    }

    @Test fun `only the settings flow slides`() {
        val settings = ScreenTransitions.enter("settings", "settings/appearance")
        val plain = ScreenTransitions.enter("home", "library")
        // A slide is an extra transition combined onto the fade, so the two cannot be equal.
        assertNotEquals(plain, settings)
        assertEquals(ScreenTransitions.enter("home", "settings"), plain)
        assertEquals(ScreenTransitions.enter("settings", "home"), plain)
        assertEquals(ScreenTransitions.enter(null, null), plain)
    }

    @Test fun `settings slides both ways and mirrors on back`() {
        val forward = ScreenTransitions.enter("settings", "settings/player", isPop = false)
        val backward = ScreenTransitions.enter("settings/player", "settings", isPop = true)
        assertNotEquals("back must not arrive from the same edge as forward", forward, backward)
    }
}
