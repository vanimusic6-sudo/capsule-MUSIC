package com.nikhil.yt.playback

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTransportTest {
    @Test
    fun disconnectDuringScheduledRetryDoesNotMissTheReconnectEvent() = runTest {
        var connected = true
        var prepares = 0
        var pauses = 0
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { connected },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { prepares > 0 },
            recoveryProgressProvider = { prepares > 0 },
            pausePlayback = { pauses++ },
            preparePlayback = { prepares++ },
            networkRetryProgressGraceMs = 100L,
        )

        coordinator.recoverFromNetworkError()
        assertTrue(coordinator.waitingForNetworkConnection.value)
        // A Wi-Fi/VPN handoff can emit false and true before the old delay runs.
        connected = false
        coordinator.onConnectivityChanged(false)
        assertTrue(coordinator.waitingForNetworkConnection.value)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, prepares)
        connected = true
        coordinator.onConnectivityChanged(true)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(1, prepares)
        assertEquals(0, pauses)
        assertFalse(coordinator.waitingForNetworkConnection.value)
    }

    @Test
    fun offlineRecoveryWaitsForConnectivityWithoutConsumingTheRetryBudget() = runTest {
        var connected = false
        var prepares = 0
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { connected },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { prepares > 0 },
            recoveryProgressProvider = { prepares > 0 },
            pausePlayback = {},
            preparePlayback = { prepares++ },
            networkRetryProgressGraceMs = 100L,
        )
        coordinator.recoverFromNetworkError()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, prepares)
        connected = true
        coordinator.onConnectivityChanged(true)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, prepares)
    }

    @Test
    fun silentBufferingAfterTransportRetryUsesNextBoundedSameStreamAttempt() = runTest {
        var ready = false
        var prepares = 0
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { ready },
            recoveryProgressProvider = { ready },
            pausePlayback = {},
            preparePlayback = { prepares += 1 },
            healthyPlaybackDelayMs = 5_000L,
            networkRetryProgressGraceMs = 100L,
        )

        coordinator.recoverFromNetworkError()
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, prepares)

        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(2, prepares)

        ready = true
        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, prepares)
    }

    @Test
    fun noPlayableFreshResolveClaimIsOneShotUntilHealthyReset() = runTest {
        val coordinator = PlaybackRecoveryCoordinator(
            scopeProvider = { this },
            maxConsecutiveTrackFailures = 3,
            currentMediaIdProvider = { "song" },
            playWhenReadyProvider = { true },
            currentIndexProvider = { 0 },
            positionGenerationProvider = { 0L },
            connectedProvider = { true },
            playbackBlockedProvider = { false },
            healthyPlaybackProvider = { true },
            recoveryProgressProvider = { true },
            pausePlayback = {},
            preparePlayback = {},
        )

        assertTrue(coordinator.claimNoPlayableFreshResolve("song"))
        assertFalse(coordinator.claimNoPlayableFreshResolve("song"))
        coordinator.resetRetry("song")
        assertTrue(coordinator.claimNoPlayableFreshResolve("song"))
    }

    @Test
    fun noPlayableStreamCauseIsClassifiedWithoutTreatingGenericRemoteErrorsAsRetryable() {
        val noStream = PlaybackException(
            "unknown",
            IllegalStateException("No playable stream found for this track."),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
        val generic = PlaybackException(
            "unknown",
            IllegalStateException("some other remote failure"),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )

        assertTrue(noStream.isNoPlayableStreamFailure())
        assertFalse(generic.isNoPlayableStreamFailure())
    }
}
