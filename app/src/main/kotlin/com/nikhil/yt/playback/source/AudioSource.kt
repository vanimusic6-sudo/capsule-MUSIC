/*
 * capsule fork
 * Playback source preferences for Capsule.
 * Based on the provider-routing idea used by MetroFuse.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.source

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

enum class AudioSource(
    val title: String,
    val shortTitle: String,
) {
    YOUTUBE("YouTube", "YT"),
    DEEZER("Deezer", "DZ"),
    AMAZON_MUSIC("Amazon Music", "AMZ"),
    ;

    companion object {
        fun fromPreference(value: String?): AudioSource =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: YOUTUBE
    }
}

enum class DeezerAudioQuality(
    val title: String,
    val badge: String,
) {
    MP3_128("MP3 · 128 kbps", "128K"),
    MP3_320("MP3 · 320 kbps", "320K"),
    FLAC("FLAC · Lossless", "FLAC"),
    ;

    companion object {
        fun fromPreference(value: String?): DeezerAudioQuality =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MP3_320
    }
}

val AudioSourceKey = stringPreferencesKey("capsule_audio_source")
val DeezerAudioQualityKey = stringPreferencesKey("capsule_deezer_audio_quality")
val DeezerFastModeKey = booleanPreferencesKey("capsule_deezer_fast_mode")
val DeezerResolverUrlKey = stringPreferencesKey("capsule_deezer_resolver_url")
