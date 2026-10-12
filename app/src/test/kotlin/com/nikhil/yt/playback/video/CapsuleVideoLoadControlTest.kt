@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.exoplayer.LoadControl
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapsuleVideoLoadControlTest {
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
