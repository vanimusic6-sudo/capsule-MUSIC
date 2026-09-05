package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRetryBudgetTest {
    @Test fun mixedNetworkAndStreamFailuresShareOneFiniteBudget() {
        val budget = PlaybackRetryBudget()
        assertEquals(1_500L, budget.nextDelayMs("track")) // network timeout
        assertEquals(3_000L, budget.nextDelayMs("track")) // rejected signed URL
        assertEquals(6_000L, budget.nextDelayMs("track")) // another timeout
        repeat(100) { assertNull(budget.nextDelayMs("track")) }
    }

    @Test fun anotherTrackAndAnExplicitResetHaveIndependentBudgets() {
        val budget = PlaybackRetryBudget(maxAttempts = 1)
        assertNotNull(budget.nextDelayMs("first"))
        assertNull(budget.nextDelayMs("first"))
        assertNotNull(budget.nextDelayMs("second"))
        budget.reset("first")
        assertNotNull(budget.nextDelayMs("first"))
        assertNull(budget.nextDelayMs("second"))
    }
}
