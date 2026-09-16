package com.nikhil.yt.ui

import com.nikhil.yt.ui.player.orbitShouldTurn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comet's clock is the most expensive thing on this screen, so its one condition is pinned.
 *
 * A running Animatable requests a frame every vsync, which wakes the whole window to recompose and
 * redraw at the display refresh rate. Left ungated it did that for as long as anything was playing
 * — including behind a collapsed sheet and behind a backgrounded app, for hours, for a dot going
 * round a circle nobody could see.
 */
class CapsuleOrbitClockTest {
    @Test fun theCometTurnsOnlyWhilePlayingAndVisible() {
        assertTrue(orbitShouldTurn(isPlaying = true, isLoading = false, visible = true))

        assertFalse(
            "a player behind a collapsed sheet is still composed, and must not run a clock",
            orbitShouldTurn(isPlaying = true, isLoading = false, visible = false),
        )
        assertFalse(
            "paused music leaves the dot where it is",
            orbitShouldTurn(isPlaying = false, isLoading = false, visible = true),
        )
        assertFalse(
            "the spinner is showing instead",
            orbitShouldTurn(isPlaying = true, isLoading = true, visible = true),
        )
    }
}
