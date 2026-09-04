/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.extensions

import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

/**
 * Enables Media3's real audio-offload path on 1.9.x.
 *
 * Media3 1.9 moved offload configuration into
 * TrackSelectionParameters.AudioOffloadPreferences and removed the old
 * ExoPlayer offload-scheduling toggles. Using the old reflection names left
 * Capsule on normal CPU decoding even when the setting said "enabled".
 */
fun ExoPlayer.setOffloadEnabled(enabled: Boolean) {
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
            // Prefer power saving whenever the selected format/device can do it.
            // Do not require gapless or speed-change support, otherwise many
            // otherwise-capable devices would silently fall back to CPU decode.
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()

    trackSelectionParameters =
        trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()

    Timber.tag("AudioOffload").i("Media3 audio offload preference enabled=%s", enabled)
}
