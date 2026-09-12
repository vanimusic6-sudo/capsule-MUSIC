package com.nikhil.yt.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFocusControllerTest {
    @Test
    fun transientLossPausesAndRemembersPlayingState() {
        val decision =
            reducePlaybackFocusChange(
                state = PlaybackFocusState(hasFocus = true),
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                isPlaying = true,
            )

        assertFalse(decision.state.hasFocus)
        assertTrue(decision.state.wasPlayingBeforeLoss)
        assertEquals(1f, decision.volumeFactor)
        assertEquals(PlaybackFocusPlaybackAction.PAUSE, decision.playbackAction)
    }

    @Test
    fun duckLowersVolumeWithoutPausing() {
        val decision =
            reducePlaybackFocusChange(
                state = PlaybackFocusState(hasFocus = true),
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                isPlaying = true,
            )

        assertFalse(decision.state.hasFocus)
        assertTrue(decision.state.wasPlayingBeforeLoss)
        assertEquals(0.2f, decision.volumeFactor)
        assertEquals(PlaybackFocusPlaybackAction.NONE, decision.playbackAction)
    }

    @Test
    fun gainResumesOnlyWhenPlaybackWasActiveBeforeLoss() {
        val decision =
            reducePlaybackFocusChange(
                state = PlaybackFocusState(wasPlayingBeforeLoss = true),
                focusChange = AudioManager.AUDIOFOCUS_GAIN,
                isPlaying = false,
            )

        assertTrue(decision.state.hasFocus)
        assertFalse(decision.state.wasPlayingBeforeLoss)
        assertEquals(1f, decision.volumeFactor)
        assertEquals(PlaybackFocusPlaybackAction.RESUME, decision.playbackAction)
    }

    @Test
    fun permanentLossClearsResumeIntent() {
        val decision =
            reducePlaybackFocusChange(
                state = PlaybackFocusState(hasFocus = true, wasPlayingBeforeLoss = true),
                focusChange = AudioManager.AUDIOFOCUS_LOSS,
                isPlaying = true,
            )

        assertFalse(decision.state.hasFocus)
        assertFalse(decision.state.wasPlayingBeforeLoss)
        assertEquals(PlaybackFocusPlaybackAction.PAUSE, decision.playbackAction)
    }

    @Test
    fun transientMayDuckGainKeepsLegacyNoResumeSemantics() {
        val decision =
            reducePlaybackFocusChange(
                state = PlaybackFocusState(wasPlayingBeforeLoss = true),
                focusChange = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                isPlaying = false,
            )

        assertTrue(decision.state.hasFocus)
        assertTrue(decision.state.wasPlayingBeforeLoss)
        assertEquals(PlaybackFocusPlaybackAction.NONE, decision.playbackAction)
    }
}
