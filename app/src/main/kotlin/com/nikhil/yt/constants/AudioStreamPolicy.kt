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

    /** Use current anonymous Web Embedded identity first. */
    WEB_EMBEDDED,

    /** Manual compatibility mode; current iOS GVS URLs can require PO-token. */
    IOS,

    /** Manual compatibility mode; pinned and may require PO-token. */
    IOS_MUSIC,

    /** Use current downgraded TV compatibility identity first. */
    TV_DOWNGRADED,

    /** Use current TVHTML5 identity first. */
    TVHTML5,
}
