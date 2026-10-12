@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.media3.common.Player

class CapsuleVideoCompanionTest {
    @Test fun preparingSurfaceMustNotRevealTheVideoWindow() {
        val preparing = CapsuleVideoPlaybackState(mode = CapsulePlaybackMode.VIDEO, phase = CapsuleVideoPhase.PREPARING)
        assertTrue(preparing.needsSurface)
        assertFalse(preparing.presentingVideo)
        assertTrue(preparing.copy(firstFrameReady = true, phase = CapsuleVideoPhase.PLAYING).presentingVideo)
        assertFalse(preparing.copy(mode = CapsulePlaybackMode.AUDIO, firstFrameReady = true).presentingVideo)
    }
    @Test fun committedVideoBufferingDrivesTheUiWhileMusicIsReady() {
        val video = CapsuleVideoPlaybackState(mode = CapsulePlaybackMode.VIDEO,
            phase = CapsuleVideoPhase.BUFFERING, firstFrameReady = true)
        assertTrue(video.presentingVideo)
        assertEquals(Player.STATE_BUFFERING, video.playbackStateOr(Player.STATE_READY))
        assertEquals(Player.STATE_READY, video.copy(phase = CapsuleVideoPhase.PLAYING).playbackStateOr(Player.STATE_BUFFERING))
        assertEquals(Player.STATE_READY, video.copy(firstFrameReady = false).playbackStateOr(Player.STATE_READY))
    }
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
