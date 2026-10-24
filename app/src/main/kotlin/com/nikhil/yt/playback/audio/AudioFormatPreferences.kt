package com.nikhil.yt.playback.audio

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.PreferAacAudioKey
import com.nikhil.yt.constants.ReleasePlaybackDefaults
import com.nikhil.yt.extensions.toEnum

/** AAC was briefly stored as a quality tier. Preserve that opt-in without exposing it as quality. */
internal fun Preferences.prefersAacAudio(): Boolean =
    this[PreferAacAudioKey] ?: (this[AudioQualityKey] == AudioQuality.AAC.name)

/** All normal tiers accept AAC. AAC here is only the internal MP4 compatibility override. */
internal fun Preferences.effectiveAudioQuality(): AudioQuality {
    if (prefersAacAudio()) return AudioQuality.AAC
    return when (val quality = this[AudioQualityKey].toEnum(ReleasePlaybackDefaults.audioQuality)) {
        AudioQuality.AAC, AudioQuality.HIGHEST -> AudioQuality.HIGH
        else -> quality
    }
}

/** Save quality and the old codec opt-in atomically, so a quality change cannot reset compatibility. */
internal fun MutablePreferences.selectAudioQuality(quality: AudioQuality) {
    if (this[PreferAacAudioKey] == null) this[PreferAacAudioKey] = prefersAacAudio()
    this[AudioQualityKey] = when (quality) {
        AudioQuality.AAC, AudioQuality.HIGHEST -> AudioQuality.HIGH
        else -> quality
    }.name
}
