package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.miniPlayerClockShouldRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mini-player's decorative clock is the one that runs during ordinary use.
 *
 * The mini-player is on screen on every page of the app for as long as something is playing, so
 * anything ticking inside it keeps the whole window's frame clock awake — all evening, on every
 * screen. Its two conditions are pinned because losing either one is invisible in review and shows
 * up only as a warm phone.
 */
class MiniPlayerClockTest {
    @Test fun itRunsOnlyWhereTheMiniPlayerCanActuallyBeSeen() {
        assertTrue(
            "collapsed and present is the case it exists for",
            miniPlayerClockShouldRun(isExpanded = false, isDismissed = false),
        )
        assertFalse(
            "the full player covers it",
            miniPlayerClockShouldRun(isExpanded = true, isDismissed = false),
        )
        assertFalse(
            "a dismissed sheet has nothing to draw on",
            miniPlayerClockShouldRun(isExpanded = false, isDismissed = true),
        )
        assertFalse(
            miniPlayerClockShouldRun(isExpanded = true, isDismissed = true),
        )
    }
}
