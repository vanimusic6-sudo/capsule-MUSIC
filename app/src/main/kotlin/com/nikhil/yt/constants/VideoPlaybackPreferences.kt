/*
 * Capsule MUSIC
 * Video playback preferences.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.constants

import androidx.datastore.preferences.core.stringPreferencesKey

val CapsuleVideoQualityKey = stringPreferencesKey("capsuleVideoQuality")

enum class CapsuleVideoQuality(
    val maxHeight: Int?,
) {
    AUTO(720),
    P360(360),
    P480(480),
    P720(720),
}
