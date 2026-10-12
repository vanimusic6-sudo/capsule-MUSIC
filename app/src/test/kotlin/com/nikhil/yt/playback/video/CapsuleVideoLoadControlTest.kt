@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapsuleVideoLoadControlTest {
    @Test fun realExoPlayerCanBeCreatedWithVideoLoadControl() {
        // Regression: ExoPlayerImplInternal queries the back buffer during its
        // constructor, before a stream has even been prepared.
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication())
            .setLoadControl(CapsuleVideoLoadControl()).build()
        try {
            assertEquals(Player.STATE_IDLE, player.playbackState)
        } finally {
            player.release()
        }
    }

    @Test fun currentMedia3CallbacksUseConcreteDefaultLoadControl() {
        val control = CapsuleVideoLoadControl()
        val playerId = PlayerId.UNSET
        val parameters = LoadControl.Parameters(playerId, Timeline.EMPTY, MediaPeriodId("clip"),
            0L, 500_000L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        assertNotNull(control.getAllocator(playerId))
        assertEquals(0L, control.getBackBufferDurationUs(playerId))
        assertFalse(control.retainBackBufferFromKeyframe(playerId))
        control.onPrepared(playerId)
        try {
            control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray<ExoTrackSelection?>())
            assertTrue(control.shouldContinueLoading(parameters))
            assertTrue(control.shouldStartPlayback(parameters))
            control.parked = true
            assertFalse(control.shouldContinueLoading(parameters))
            assertFalse(control.shouldContinuePreloading(playerId, parameters.timeline, parameters.mediaPeriodId, 0L))
            control.onStopped(playerId)
        } finally {
            control.onReleased(playerId)
        }
    }

    @Test fun parkedClipDoesNotAskItsLoaderForMoreData() {
        var checks = 0
        val delegate = Proxy.newProxyInstance(LoadControl::class.java.classLoader, arrayOf(LoadControl::class.java)) { _, method, _ ->
            if (method.name == "shouldContinueLoading") { checks++; true } else null
        } as LoadControl
        val control = CapsuleVideoLoadControl(delegate)
        control.parked = true
        // The parked branch must not access a player-specific buffer or schedule any load.
        val parameters = LoadControl.Parameters(PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("clip"),
            0L, 0L, 1f, false, false, C.TIME_UNSET, C.TIME_UNSET)
        assertFalse(control.shouldContinueLoading(parameters))
        assertEquals(0, checks)
        control.parked = false
        assertTrue(control.shouldContinueLoading(parameters))
        assertEquals(1, checks)
    }
}
