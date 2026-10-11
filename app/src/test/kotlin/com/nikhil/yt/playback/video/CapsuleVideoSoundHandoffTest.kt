package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoSoundHandoffTest {
    @Test fun neverMuteAudioBeforeFirstActualVideoFrame() {
        assertFalse(canSwitchToVideoSound(
            firstFrameRendered = false, ready = true,
            originalAudioSelected = true,
            musicIsPlaying = true, clipIsPlaying = true,
        ))
    }

    @Test fun neverMuteAudioBeforeVideoSoundtrackIsSelected() {
        assertFalse(canSwitchToVideoSound(
            firstFrameRendered = true, ready = true,
            originalAudioSelected = false,
            musicIsPlaying = true, clipIsPlaying = true,
        ))
    }

    @Test fun bufferingOrPausedVideoNeverTakesSound() {
        assertFalse(canSwitchToVideoSound(true, false, true, true, false))
        assertFalse(canSwitchToVideoSound(true, true, true, true, false))
        assertFalse(canSwitchToVideoSound(true, true, true, false, true))
    }

    @Test fun onlyRealPlayingVideoWithItsOwnAudioCanHandoff() {
        assertTrue(canSwitchToVideoSound(true, true, true, true, true))
    }
}
