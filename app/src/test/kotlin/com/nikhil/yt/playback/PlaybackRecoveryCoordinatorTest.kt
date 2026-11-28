package com.nikhil.yt.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    /**
     * A rejected signed URL names one URL on one CDN node, so the first one buys the same client a
     * different node; only the second implicates the client. These counts are what
     * MusicService compares against SIGNED_URL_REJECTIONS_BEFORE_CLIENT_ROLLOVER.
     */
    @Test
    fun explicitReselectionRearmsOnlyTheLocalCdnBudget() {
        val coordinator = coordinator()
        assertEquals(AudioCdnRecoveryAction.TRY_FRESH_URL_SAME_CLIENT,
            coordinator.onCdnFailure("track", signedUrlRejected = false, unresponsiveOpen = true))
        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK,
            coordinator.onCdnFailure("track", signedUrlRejected = false, unresponsiveOpen = true))
        coordinator.recordSignedUrlRejection("track")
        coordinator.recordTerminalFailure("track", true)
        assertFalse(coordinator.onExplicitTrackSelection("unrelated"))
        assertTrue(coordinator.onExplicitTrackSelection("track"))
        assertFalse(coordinator.onExplicitTrackSelection("track"))
        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
        assertEquals(AudioCdnRecoveryAction.TRY_FRESH_URL_SAME_CLIENT,
            coordinator.onCdnFailure("track", signedUrlRejected = false, unresponsiveOpen = true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK,
            coordinator.onCdnFailure("track", signedUrlRejected = false, unresponsiveOpen = true))
        assertEquals(2, coordinator.recordSignedUrlRejection("track"))
        assertFalse(coordinator.recordTerminalFailure("track", true).mayAutoSkip)
    }

    @Test
    fun signedUrlRejectionsAreCountedPerSong() {
        val coordinator = coordinator()

        assertEquals(1, coordinator.recordSignedUrlRejection("track"))
        assertEquals(2, coordinator.recordSignedUrlRejection("track"))
        assertEquals(3, coordinator.recordSignedUrlRejection("track"))

        // Another song starts clean: one node rejecting one URL says nothing about the next song.
        assertEquals(1, coordinator.recordSignedUrlRejection("other"))
        assertEquals(4, coordinator.recordSignedUrlRejection("track"))
    }

    @Test
    fun signedUrlRejectionsAreForgottenWhenTheSongRecovers() {
        val coordinator = coordinator()

        assertEquals(1, coordinator.recordSignedUrlRejection("track"))
        assertEquals(2, coordinator.recordSignedUrlRejection("track"))

        // Healthy playback and explicit user action both route through resetRetry, so a song that
        // played fine must not carry its old rejections into a later failure.
        coordinator.resetRetry("track")
        assertEquals(1, coordinator.recordSignedUrlRejection("track"))

        coordinator.recordSignedUrlRejection("other")
        coordinator.clearRetryBudget()
        assertEquals(1, coordinator.recordSignedUrlRejection("track"))
        assertEquals(1, coordinator.recordSignedUrlRejection("other"))
    }

    @Test
    fun signedUrlRejectionMemoryStaysBounded() {
        val coordinator = coordinator()

        // A long queue must not turn this into an unbounded map.
        repeat(400) { coordinator.recordSignedUrlRejection("track-$it") }

        // The oldest entries are evicted, so they simply start over rather than leaking.
        assertEquals(1, coordinator.recordSignedUrlRejection("track-0"))
        assertEquals(2, coordinator.recordSignedUrlRejection("track-399"))
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

    @Test
    fun reconnectDuringPrepareGraceRetainsIntentAndDuplicateErrorsDoNotSpendRetries() = runTest {
        var connected = true
        var prepared = 0
        var ready = false
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "track" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { connected },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { ready },
            pausePlayback = {},
            preparePlayback = { prepared++ },
        )
        coordinator.recoverFromNetworkError()
        coordinator.recoverFromNetworkError()
        advanceTimeBy(1_501L); runCurrent()
        assertEquals(1, prepared)
        assertTrue(coordinator.isNetworkRecoveryActive())
        assertFalse(coordinator.waitingForNetworkConnection.value)
        connected = false
        coordinator.onConnectivityChanged(false)
        assertTrue(coordinator.waitingForNetworkConnection.value)
        connected = true
        coordinator.onConnectivityChanged(true)
        advanceTimeBy(3_001L); runCurrent()
        assertEquals(2, prepared)
        ready = true
        advanceTimeBy(5_001L); runCurrent()
        assertFalse(coordinator.isNetworkRecoveryActive())
        assertEquals(6_000L, coordinator.nextRetryDelayMs("track"))
    }

    @Test
    fun streamBudgetRequiresSustainedActualProgressAndIgnoresDuplicateReadyEvents() = runTest {
        var position = 100L
        var resets = 0
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "track" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { true },
            pausePlayback = {},
            preparePlayback = {},
            playbackPositionMsProvider = { position },
            onHealthyStream = { resets++ },
        )
        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
        coordinator.onPlaybackActivity("track", true, true)
        advanceTimeBy(5_001L)
        position += 5_000L
        coordinator.onPlaybackActivity("track", true, true)
        assertEquals(3_000L, coordinator.nextRetryDelayMs("track"))
        advanceTimeBy(25_000L); runCurrent()
        assertEquals(1, resets)
        assertEquals(1_500L, coordinator.nextRetryDelayMs("track"))
        coordinator.onPlaybackActivity("track", false, false)
        coordinator.onPlaybackActivity("track", true, true)
        advanceTimeBy(30_001L); runCurrent()
        assertEquals(1, resets)
        assertEquals(3_000L, coordinator.nextRetryDelayMs("track"))
    }

    @Test
    fun seekGenerationChangeCancelsDelayedNetworkPrepare() = runBlocking {
        var positionGeneration = 0L
        var prepareCalls = 0
        val coordinator =
            PlaybackRecoveryCoordinator(
                scopeProvider = { this },
                maxConsecutiveTrackFailures = 3,
                currentMediaIdProvider = { "track" },
                playWhenReadyProvider = { true },
                currentIndexProvider = { 0 },
                positionGenerationProvider = { positionGeneration },
                connectedProvider = { true },
                playbackBlockedProvider = { false },
                healthyPlaybackProvider = { false },
                pausePlayback = {},
                preparePlayback = { prepareCalls += 1 },
            )

        coordinator.recoverFromNetworkError()
        assertTrue(coordinator.waitingForNetworkConnection.value)

        positionGeneration += 1L
        delay(1_650L)

        assertEquals(0, prepareCalls)
        assertFalse(coordinator.waitingForNetworkConnection.value)
    }
}
