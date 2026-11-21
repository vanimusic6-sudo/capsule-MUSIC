@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.media3.common.Player
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery

class CapsuleVideoCompanionTest {
    @Test fun progressiveColdSeekLeadsAdvancingMusicOnce() {
        assertEquals(1_700L, predictedVideoSeekLeadMs(
            musicPlaying = true, targetMs = 3_800L, bufferedUntilMs = 2_000L,
            previousSeekMs = 0L, initialReadyMs = 1_700L,
        ))
        assertEquals(1_840L, predictedVideoSeekLeadMs(
            musicPlaying = true, targetMs = 3_800L, bufferedUntilMs = 2_000L,
            previousSeekMs = 1_840L, initialReadyMs = 1_700L,
        ))
    }

    @Test fun bufferedPausedAndUnknownStartupNeverOvershoot() {
        assertEquals(0L, predictedVideoSeekLeadMs(true, 3_800L, 6_000L, 0L, 1_700L))
        assertEquals(0L, predictedVideoSeekLeadMs(false, 3_800L, 2_000L, 0L, 1_700L))
        assertEquals(0L, predictedVideoSeekLeadMs(true, 3_800L, 2_000L, 0L, 0L))
        assertEquals(2_000L, predictedVideoSeekLeadMs(true, 3_800L, 2_000L, 2_500L, 1_700L))
    }

    @Test fun videoStartupEstimatorLearnsOnlyFromPreparedDashAndSabr() {
        val estimator = CapsuleVideoReadinessEstimator()
        assertEquals(null, estimator.prediction(CapsuleVideoDelivery.DASH))
        estimator.observe(CapsuleVideoDelivery.DASH, 2_500L)
        assertEquals("A single potentially anomalous clip cannot alter startup seek",
            null, estimator.prediction(CapsuleVideoDelivery.DASH))
        estimator.observe(CapsuleVideoDelivery.DASH, 1_500L)
        assertEquals(2_250L, estimator.prediction(CapsuleVideoDelivery.DASH))
        assertEquals(null, estimator.prediction(CapsuleVideoDelivery.SABR))
        estimator.observe(CapsuleVideoDelivery.DASH, 40_000L)
        assertEquals("Outliers must not bias the next video", 2_250L,
            estimator.prediction(CapsuleVideoDelivery.DASH))
        estimator.observe(CapsuleVideoDelivery.SABR, 4_000L)
        estimator.observe(CapsuleVideoDelivery.SABR, 5_000L)
        assertEquals(4_250L, estimator.prediction(CapsuleVideoDelivery.SABR))
        estimator.observe(CapsuleVideoDelivery.PROGRESSIVE, 2_000L)
        estimator.observe(CapsuleVideoDelivery.PROGRESSIVE, 1_000L)
        assertEquals(null, estimator.prediction(CapsuleVideoDelivery.PROGRESSIVE))
    }

    @Test fun indexedDashDurationSupportsHoursMinutesFractionalSeconds() {
        assertEquals(241_500L, capsuleIndexedDashDurationMs(
            """<MPD mediaPresentationDuration="PT4M1.5S"/>""",
        ))
        assertEquals(3_780_000L, capsuleIndexedDashDurationMs(
            """<MPD mediaPresentationDuration='PT1H3M'/>""",
        ))
        assertEquals(250_000L, capsuleIndexedDashDurationMs(
            """<MPD mediaPresentationDuration="PT250S"/>""",
        ))
        assertEquals(null, capsuleIndexedDashDurationMs("""<MPD mediaPresentationDuration="P3D"/>"""))
        assertEquals(null, capsuleIndexedDashDurationMs("""<MPD mediaPresentationDuration="garbage"/>"""))
        assertEquals(null, capsuleIndexedDashDurationMs("x".repeat(64 * 1024 + 1)))
    }

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
