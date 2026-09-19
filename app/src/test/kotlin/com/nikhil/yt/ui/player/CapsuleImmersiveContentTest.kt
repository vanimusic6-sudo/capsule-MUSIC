package com.nikhil.yt.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleImmersiveContentTest {
    @Test
    fun `controls retreat from a screen being listened to`() {
        assertTrue(immersiveChromeShouldRetreat(visible = true, isPlaying = true, chromeShown = true))
    }

    @Test
    fun `nothing counts down behind a collapsed sheet or a backgrounded app`() {
        assertFalse(
            "a timer running for nobody is the most expensive thing an idle screen can do",
            immersiveChromeShouldRetreat(visible = false, isPlaying = true, chromeShown = true),
        )
    }

    @Test
    fun `a paused player keeps its controls`() {
        assertFalse(
            "a paused screen is one being looked at, not listened to",
            immersiveChromeShouldRetreat(visible = true, isPlaying = false, chromeShown = true),
        )
    }

    @Test
    fun `nothing is armed once the controls are already away`() {
        assertFalse(
            immersiveChromeShouldRetreat(visible = true, isPlaying = true, chromeShown = false),
        )
    }

    @Test
    fun `a screen that is off and paused arms nothing either`() {
        assertFalse(immersiveChromeShouldRetreat(visible = false, isPlaying = false, chromeShown = true))
        assertFalse(immersiveChromeShouldRetreat(visible = false, isPlaying = false, chromeShown = false))
    }
}
