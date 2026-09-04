/*
 * Capsule MUSIC
 * Safe AUDIO stream policy.
 *
 * Only reviewed InnerTubeX-backed profiles are exposed to users. Legacy enum
 * values are retained solely as migration tombstones so upgrades from older
 * Capsule builds can safely fall back to AUTO_SAFE without reviving the old
 * playback resolver.
 *
 * GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.datastore.preferences.core.stringPreferencesKey

val AudioStreamPolicyKey =
    stringPreferencesKey("capsuleAudioStreamPolicy")

enum class AudioStreamPolicy {
    /** Recommended: let InnerTubeX select only compatible automatic profiles. */
    AUTO_SAFE,

    /** Fast direct visionOS compatibility profile. */
    VISIONOS,

    /** Stable Web Embedded profile. */
    WEB_EMBEDDED,

    /** Stable Web Remix profile with current PO-token handling. */
    WEB,

    /**
     * Legacy migration tombstones. They are never exposed in normal settings
     * and are normalized to AUTO_SAFE before playback.
     */
    MWEB,
    IOS,
    IOS_MUSIC,
    TV_DOWNGRADED,

    /** Modern TV fallback maps to TVHTML5_SIMPLY in InnerTubeX. */
    TVHTML5,
    ;

    /** Profiles that are deliberately exposed to users. */
    val isUserSelectable: Boolean
        get() =
            when (this) {
                AUTO_SAFE,
                VISIONOS,
                WEB_EMBEDDED,
                WEB,
                TVHTML5,
                -> true

                MWEB,
                IOS,
                IOS_MUSIC,
                TV_DOWNGRADED,
                -> false
            }

    /**
     * Never allow an obsolete persisted preference to reactivate a legacy or
     * known-bad client. Old values become AUTO_SAFE at the AUDIO boundary.
     */
    fun normalizedForPlayback(): AudioStreamPolicy =
        if (isUserSelectable) this else AUTO_SAFE
}
