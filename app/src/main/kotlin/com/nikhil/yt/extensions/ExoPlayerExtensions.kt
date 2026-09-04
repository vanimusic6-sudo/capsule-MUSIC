/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.extensions

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

/**
 * Applies Capsule's low-power audio playback policy to an ExoPlayer.
 *
 * Media3 1.9 moved offload configuration into
 * TrackSelectionParameters.AudioOffloadPreferences and removed the old
 * ExoPlayer offload-scheduling toggles. Using the old reflection names left
 * Capsule on normal CPU decoding even when the setting said "enabled".
 *
 * Capsule used to build the music player with WAKE_MODE_NETWORK. That keeps a
 * WifiLock in addition to the CPU wake lock while playback is READY/BUFFERING.
 * Streaming music does not require low-latency Wi-Fi, so force WAKE_MODE_LOCAL
 * on the already-created player. The foreground media service still keeps
 * playback alive with the screen off, but Wi-Fi is free to use its normal
 * power-saving behaviour between network reads.
 */
fun ExoPlayer.setOffloadEnabled(enabled: Boolean) {
    setWakeMode(C.WAKE_MODE_LOCAL)

    val mode =
        if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }

    val offloadPreferences =
        TrackSelectionParameters.AudioOffloadPreferences
            .Builder()
            .setAudioOffloadMode(mode)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()

    trackSelectionParameters =
        trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()

    Timber.tag("AudioOffload").i(
        "Media3 low-power audio policy offload=%s wakeMode=LOCAL",
        enabled,
    )
}
