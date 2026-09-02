/*
 * Capsule MUSIC
 * Safe AUDIO stream policy.
 *
 * This intentionally replaces the old "pretend to be client X" preference
 * with a small allowlist whose members are reviewed against current upstream
 * PO-token requirements.
 *
 * GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.datastore.preferences.core.stringPreferencesKey

val AudioStreamPolicyKey =
    stringPreferencesKey("capsuleAudioStreamPolicy")

enum class AudioStreamPolicy {
    /**
     * Recommended.
     *
     * Try only Capsule's reviewed safe allowlist in priority order.
     * The actual versions are synchronized by GitHub Actions.
     */
    AUTO_SAFE,

    /** Use current visionOS identity first, then safe fallbacks. */
    VISIONOS,

    /** Use the current iOS identity first. Same family as visionOS. */
    IOS,

    /** Use the iOS Music identity first. Version is pinned, not CI-synced. */
    IOS_MUSIC,

    /** Use current downgraded TV compatibility identity first. */
    TV_DOWNGRADED,

    /** Use current TVHTML5 identity first. */
    TVHTML5,
}
