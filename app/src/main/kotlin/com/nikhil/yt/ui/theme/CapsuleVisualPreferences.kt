/**
 * Velune / Capsule MUSIC
 * Capsule visual layer preferences
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.theme

import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Enables the standalone Capsule full-player skin.
 *
 * Playback implementation is not changed by this setting.
 */
val CapsulePlayerEnabledKey =
    booleanPreferencesKey("capsulePlayerEnabled")

/**
 * Enables the Capsule visual shell around lyrics.
 *
 * Lyrics providers, fetching and synchronization remain Velune-owned.
 */
val CapsuleLyricsEnabledKey =
    booleanPreferencesKey("capsuleLyricsEnabled")

/**
 * Convenience preference for enabling the complete Capsule visual language.
 *
 * This is only a UI preference. It must never be used by playback,
 * networking, Innertube or cache code.
 */
val CapsuleFullImmersionEnabledKey =
    booleanPreferencesKey("capsuleFullImmersionEnabled")
