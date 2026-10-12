/* Capsule MUSIC — no background loading for a parked VIDEO decoder. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

internal class CapsuleVideoLoadControl(
    private val delegate: LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_500, 12_000, 350, 750)
        .setPrioritizeTimeOverSizeThresholds(true).build(),
) : LoadControl by delegate {
    @Volatile var parked = false
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        !parked && delegate.shouldContinueLoading(parameters)
}
