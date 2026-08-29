/**
 * Velune / Capsule MUSIC
 * Capsule visual layer preferences
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.theme

import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Capsule Mini Player visual skin.
 *
 * Swipe/navigation/playback behaviour remains Velune-owned.
 */
val CapsuleMiniPlayerEnabledKey =
    booleanPreferencesKey("capsuleMiniPlayerEnabled")

/**
 * Capsule full-player visual skin.
 *
 * PlayerConnection and playback service remain Velune-owned.
 */
val CapsulePlayerEnabledKey =
    booleanPreferencesKey("capsulePlayerEnabled")

/**
 * Capsule lyrics visual shell.
 *
 * Lyrics fetching, providers, database and sync remain Velune-owned.
 */
val CapsuleLyricsEnabledKey =
    booleanPreferencesKey("capsuleLyricsEnabled")
