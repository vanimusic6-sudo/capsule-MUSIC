@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoCompanionTest {
    @Test fun audioRemainsAudibleBeforeFirstVideoFrame() {
        assertFalse(canSwitchToVideoSound(
            firstFrameRendered = false,
            ready = true,
            originalAudioSelected = true,
            musicIsPlaying = true,
            clipIsPlaying = true,
        ))
    }

    @Test fun audioRemainsAudibleUntilOriginalClipSoundtrackIsSelected() {
        assertFalse(canSwitchToVideoSound(true, true, false, true, true))
    }

    @Test fun soundHandoffOnlyAfterActualPlayback() {
        assertFalse(canSwitchToVideoSound(true, false, true, true, true))
        assertFalse(canSwitchToVideoSound(true, true, true, true, false))
        assertFalse(canSwitchToVideoSound(true, true, true, false, true))
        assertTrue(canSwitchToVideoSound(true, true, true, true, true))
    }
}
