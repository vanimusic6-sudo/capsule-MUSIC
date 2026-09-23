package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNetworkSelectionPolicyTest {
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
        ))
    }

    @Test fun videoPlaybackCannotLaunchNormalAudioLookahead() {
        assertFalse(canResolveUpcomingAudioWhile(
            currentIsPlaying = true, currentPositionMs = 15_000L, currentIsVideo = true,
        ))
    }
}
