/*
 * Capsule MUSIC
 * Safe AUDIO stream policy.
 *
 * visionOS is the default. Web profiles can be selected explicitly for
 * playback and diagnostics without silently falling back to another client.
 *
 * GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.datastore.preferences.core.stringPreferencesKey

val AudioStreamPolicyKey =
    stringPreferencesKey("capsuleAudioStreamPolicy")

enum class AudioStreamPolicy {
    /** Legacy automatic value. It normalizes to [VISIONOS]. */
    AUTO_SAFE,

    /** Fast direct visionOS compatibility profile. */
    VISIONOS,

    /** Explicit embedded web profile. */
    WEB_EMBEDDED,

    /** YouTube Music web profile; keeps the existing WEB preference value. */
    WEB,

    /**
     * Migration tombstones. They are never exposed in normal settings and are
     * normalized to VISIONOS before playback. TVHTML5 remains here only so an
     * older saved preference cannot reactivate a profile whose bounded-range
     * transport Capsule does not implement yet.
     */
    MWEB,
    IOS,
    IOS_MUSIC,
    TV_DOWNGRADED,
    TVHTML5,
    ;

    val isUserSelectable: Boolean
        get() = this == VISIONOS || this == WEB || this == WEB_EMBEDDED

    fun normalizedForPlayback(): AudioStreamPolicy =
        if (isUserSelectable) this else VISIONOS

    val playbackClientOverrideId: String
        get() = when (normalizedForPlayback()) {
            WEB -> "WEB_REMIX"
            WEB_EMBEDDED -> "WEB_EMBEDDED_PLAYER"
            else -> "VISIONOS"
        }
}
