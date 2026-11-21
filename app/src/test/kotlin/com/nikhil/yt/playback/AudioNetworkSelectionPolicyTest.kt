package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNetworkSelectionPolicyTest {
    @Test fun actualLoaderCanPrepareNextWhilePausedOrBeforeThreeSecondsWithoutCancellationRetries() {
        assertFalse(canResolveUpcomingAudioWhile(false, 0L, false))
        assertTrue(isAudioLoaderSelectionRelevant("next", "current", listOf("next"), false))
        assertTrue(isAudioLoaderSelectionRelevant("current", "current", listOf("next"), false))
        assertFalse(isAudioLoaderSelectionRelevant("abandoned", "current", listOf("next"), false))
        assertFalse(isAudioLoaderSelectionRelevant("next", "video", listOf("next"), true))
    }

    @Test fun swipingPastAnUnwarmedTrackNeverStartsLookaheadWhileTheNewTrackBuffers() {
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = false, currentPositionMs = 0L, currentIsVideo = false,
        ))
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = false, currentPositionMs = 30_000L, currentIsVideo = false,
        ))
    }

    @Test fun foregroundAudioMustActuallyPlayBeforeStartingAnotherPlayerRequest() {
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true,
            currentPositionMs = AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS - 1L,
            currentIsVideo = false,
        ))
        assertTrue(canResolveUpcomingAudioWhile(
            currentIsPlaying = true,
            currentPositionMs = AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS,
            currentIsVideo = false,
            currentBufferedAheadMs = AUDIO_PREFETCH_SAFE_BUFFER_MS,
        ))
    }

    @Test fun twentySecondsPlayedDoesNotOverrideEmptyOrWeakBuffer() {
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 20_000L,
            currentIsVideo = false, currentBufferedAheadMs = 0L,
        ))
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 20_000L,
            currentIsVideo = false, currentBufferedAheadMs = 3_000L,
        ))
        assertTrue(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 20_000L,
            currentIsVideo = false, currentBufferedAheadMs = 12_000L,
        ))
    }

    @Test fun fullyBufferedNaturalTrackEndAllowsGaplessLookahead() {
        assertEquals(4_000L, audioPrefetchRequiredBufferMs(26_000L, 30_000L))
        assertTrue(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 26_000L,
            currentIsVideo = false, currentBufferedAheadMs = 4_000L,
            currentDurationMs = 30_000L,
        ))
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 26_000L,
            currentIsVideo = false, currentBufferedAheadMs = 1_000L,
            currentDurationMs = 30_000L,
        ))
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 30_000L,
            currentIsVideo = false, currentBufferedAheadMs = 0L,
            currentDurationMs = 30_000L,
        ))
        assertEquals(AUDIO_PREFETCH_SAFE_BUFFER_MS,
            audioPrefetchRequiredBufferMs(15_000L, -1L))
    }

    @Test fun videoPlaybackCannotLaunchNormalAudioLookahead() {
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 15_000L, currentIsVideo = true,
        ))
    }
}
