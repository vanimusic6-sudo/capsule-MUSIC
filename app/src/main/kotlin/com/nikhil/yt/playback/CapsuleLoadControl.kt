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
 * The policy started as Metrolist's 50s/50s/750ms/2000ms and has since been widened for long
 * content; the reasons are on each value below. None of it inflates the AudioTrack buffer or
 * changes the cache format, and the 750ms playback threshold still keeps normal startup responsive.
 */

/**
 * Three minutes of runway, not fifty seconds.
 *
 * Fifty seconds is ample for a song, and a song is what this was tuned on. An hour-long episode on
 * a link that is only just fast enough is a different problem: a capture of one showed the CDN
 * delivering about 9.7 kB/s for a stream that needs 16.3, with the difference covered by whatever
 * had been buffered ahead. With fifty seconds of runway that lasts under a minute, and from then on
 * it stalls every few seconds for the rest of the episode.
 *
 * Three minutes does not create bandwidth and does not rescue a link that is slow for an hour. What
 * it does is let a link that is *intermittently* fast bank the good stretches and spend them on the
 * bad ones, which is the actual shape of a mobile connection. It costs a few megabytes of memory.
 */
internal const val CAPSULE_MIN_BUFFER_MS = 180_000
internal const val CAPSULE_MAX_BUFFER_MS = 180_000
internal const val CAPSULE_BUFFER_FOR_PLAYBACK_MS = 750

/**
 * Resuming after a stall waits for a real cushion, not two seconds.
 *
 * Two seconds is why a struggling stream stutters rather than pauses: the same capture shows it
 * resuming at two to three seconds buffered and stalling again almost at once, over and over. The
 * total time spent waiting is the same either way — that is set by the link, not by this number —
 * but it arrives as one pause instead of a dozen, which is the difference between an episode that
 * is unlistenable and one that is merely slow to start.
 */
internal const val CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 8_000

/**
 * Enough bytes for the three minutes above at any bitrate Capsule plays.
 *
 * Media3 stops loading at whichever of the two limits it reaches first, and its own default for an
 * audio-only track is 832 kB — about fifty seconds of 160 kbit/s Opus, which is why raising only
 * the duration would have changed nothing at all.
 */
internal const val CAPSULE_TARGET_BUFFER_BYTES = 6 * 1024 * 1024

internal fun createCapsuleLoadControl(): LoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            CAPSULE_MIN_BUFFER_MS,
            CAPSULE_MAX_BUFFER_MS,
            CAPSULE_BUFFER_FOR_PLAYBACK_MS,
            CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setTargetBufferBytes(CAPSULE_TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
