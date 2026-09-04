/*
 * Capsule MUSIC
 * Safe AUDIO stream policy.
 *
 * Playback is temporarily pinned to visionOS. Current WEB profiles require
 * signature/EJS work that can fail after a rapid-switch burst and leave every
 * following track without a playable URL. The old enum values remain only as
 * preference migration tombstones.
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

    /** Retired until its cipher path is reliable. */
    WEB_EMBEDDED,

    /** Retired until its PO-token and cipher path is reliable. */
    WEB,

    /**
     * Migration tombstones. They are never exposed in normal settings and are
     * normalized to AUTO_SAFE before playback. TVHTML5 remains here only so an
     * older saved preference cannot reactivate a profile whose bounded-range
     * transport Capsule does not implement yet.
     */
    MWEB,
    IOS,
    IOS_MUSIC,
    TV_DOWNGRADED,
    TVHTML5,
    ;

    /** Only the profile proven stable by the current device diagnostics. */
    val isUserSelectable: Boolean
        get() = this == VISIONOS

    /**
     * Never let an existing saved WEB/AUTO preference reactivate the failing
     * cipher path. A later InnerTubeX upgrade can deliberately reopen profiles
     * after they pass the same rapid-switch test.
     */
    fun normalizedForPlayback(): AudioStreamPolicy = VISIONOS
}
