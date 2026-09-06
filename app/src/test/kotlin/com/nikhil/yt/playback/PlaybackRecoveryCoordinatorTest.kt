package com.nikhil.yt.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryCoordinatorTest {
    private fun coordinator() =
        PlaybackRecoveryCoordinator(
            scopeProvider = { CoroutineScope(Dispatchers.Unconfined) },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "track" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { false },
            pausePlayback = {},
            preparePlayback = {},
        )

    @Test
    fun retryBudgetIsSharedAndBounded() {
        val coordinator = coordinator()

        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
        assertEquals(3_000L, coordinator.nextRetryDelayMs("track"))
        assertEquals(6_000L, coordinator.nextRetryDelayMs("track"))
        assertNull(coordinator.nextRetryDelayMs("track"))

        coordinator.resetRetry("track")
        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
    }

    @Test
    fun duplicateTerminalCallbackNeverSkipsTwice() {
        val coordinator = coordinator()

        val first = coordinator.recordTerminalFailure("a", autoSkipEnabled = true)
        val duplicate = coordinator.recordTerminalFailure("a", autoSkipEnabled = true)

        assertEquals(TerminalPlaybackAction.SKIP, first.action)
        assertTrue(first.mayAutoSkip)
        assertEquals(TerminalPlaybackAction.STOP, duplicate.action)
        assertFalse(duplicate.mayAutoSkip)
        assertEquals(1, duplicate.failureCount)
    }

    @Test
    fun thirdDistinctFailureOpensCircuitAndStopsTraversal() {
        val coordinator = coordinator()

        assertEquals(TerminalPlaybackAction.SKIP, coordinator.recordTerminalFailure("a", true).action)
        assertEquals(TerminalPlaybackAction.SKIP, coordinator.recordTerminalFailure("b", true).action)
        val third = coordinator.recordTerminalFailure("c", true)

        assertEquals(TerminalPlaybackAction.STOP, third.action)
        assertTrue(third.circuitOpenedNow)
        assertTrue(third.circuitOpen)
        assertEquals(3, third.failureCount)
    }

    @Test
    fun explicitFailureResetReopensTraversal() {
        val coordinator = coordinator()

        coordinator.recordTerminalFailure("a", true)
        coordinator.recordTerminalFailure("b", true)
        coordinator.recordTerminalFailure("c", true)
        coordinator.resetFailureGuard()

        val afterReset = coordinator.recordTerminalFailure("d", true)
        assertEquals(TerminalPlaybackAction.SKIP, afterReset.action)
        assertFalse(afterReset.circuitOpen)
        assertEquals(1, afterReset.failureCount)
    }

    @Test
    fun offlineRecoveryKeepsWaitingFlagAndBareCancelCanPreserveIt() {
        val coordinator =
            PlaybackRecoveryCoordinator(
                scopeProvider = { CoroutineScope(Dispatchers.Unconfined) },
                maxConsecutiveTrackFailures = 3,
                currentMediaIdProvider = { "track" },
                playWhenReadyProvider = { true },
                currentIndexProvider = { 0 },
                positionGenerationProvider = { 0L },
                connectedProvider = { false },
                playbackBlockedProvider = { false },
                healthyPlaybackProvider = { false },
                pausePlayback = {},
                preparePlayback = {},
            )

        coordinator.recoverFromNetworkError()
        assertTrue(coordinator.waitingForNetworkConnection.value)

        coordinator.cancelNetworkRecovery(clearWaiting = false)
        assertTrue(coordinator.waitingForNetworkConnection.value)

        coordinator.cancelNetworkRecovery()
        assertFalse(coordinator.waitingForNetworkConnection.value)
    }
}
