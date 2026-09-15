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
