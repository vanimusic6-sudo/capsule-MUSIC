@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Keep a real time-based safety margin for YouTube audio.
 *
 * Capsule media items are resolved from an opaque media id to an HTTPS CDN URL at load time and
 * may cross from a cached span back to googlevideo in the middle of a song. A CDN open can take
 * around two seconds, so loading must not be allowed to stop merely because Media3's byte target
 * has been reached while the time buffer is running down.
 *
 * The 50s/50s/750ms/2000ms durations intentionally mirror Metrolist's current playback policy.
 * Capsule additionally prioritizes time over size thresholds; this is the guard for the captured
 * cache-to-CDN handoff underrun.
 */
internal const val CAPSULE_MIN_BUFFER_MS = 50_000
internal const val CAPSULE_MAX_BUFFER_MS = 50_000
internal const val CAPSULE_BUFFER_FOR_PLAYBACK_MS = 750
internal const val CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

internal fun createCapsuleLoadControl(): LoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            CAPSULE_MIN_BUFFER_MS,
            CAPSULE_MAX_BUFFER_MS,
            CAPSULE_BUFFER_FOR_PLAYBACK_MS,
            CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
