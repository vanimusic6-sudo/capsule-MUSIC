package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAutoSkipPolicyTest {
    @Test fun ordinaryTerminalFailureRespectsAutoSkipSetting() {
        assertTrue(mayAutoSkipAfterTerminalFailure(
            autoSkipRequested = true, rejectedStream = false, manualRetry = false
        ))
        assertFalse(mayAutoSkipAfterTerminalFailure(
            autoSkipRequested = false, rejectedStream = false, manualRetry = false
        ))
    }

    @Test fun rejectedCdnStreamNeverStartsThreeSongTraversal() {
        assertFalse(mayAutoSkipAfterTerminalFailure(
            autoSkipRequested = true, rejectedStream = true, manualRetry = false
        ))
    }

    @Test fun explicitRetryDoesNotAutoSkipRegardlessOfErrorOrSetting() {
        assertFalse(mayAutoSkipAfterTerminalFailure(
            autoSkipRequested = true, rejectedStream = false, manualRetry = true
        ))
        assertFalse(mayAutoSkipAfterTerminalFailure(
            autoSkipRequested = true, rejectedStream = true, manualRetry = true
        ))
    }
}
