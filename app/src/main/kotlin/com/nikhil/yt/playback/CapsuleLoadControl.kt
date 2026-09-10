@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Keep a real network-sized safety margin for Capsule audio.
 *
 * Normal audio MediaItems enter Media3 with an opaque/no-scheme URI and are resolved to the
 * signed HTTPS googlevideo URL later by ResolvingDataSource. Media3 1.9.x can therefore apply its
 * local-playback buffering defaults to the original item even though the actual source is remote.
 * The captured underrun matched that failure mode: a cache-to-CDN handoff began only about one
 * second before the source buffer emptied, while opening the next CDN range took almost two.
 *
 * Explicitly use Metrolist's current 50s/50s/750ms/2000ms policy. This preserves a large source
 * buffer across cache/CDN handoffs without inflating the AudioTrack buffer or changing the cache
 * format. The 750ms playback threshold also keeps normal startup responsive.
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
        .build()
