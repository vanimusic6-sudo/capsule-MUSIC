package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer policy, stated in the terms that made it necessary.
 *
 * A capture of an hour-long episode showed the CDN delivering about 9.7 kB/s for a stream needing
 * 16.3, with the shortfall covered out of what had been buffered ahead. Fifty seconds of runway is
 * gone inside a minute of that, and the rest of the episode stutters. None of these numbers create
 * bandwidth; they decide how much of a good stretch can be banked, and whether the waiting arrives
 * as one pause or as a dozen.
 */
class CapsuleLoadControlTest {
    /** Opus at 160 kbit/s, which is what itag 251 costs and what these values are sized against. */
    private val bytesPerSecond = 16_273

    @Test
    fun theRunwayOutlastsASongBySeveralMinutes() {
        assertTrue(
            "${CAPSULE_MIN_BUFFER_MS}ms is not enough runway for long content",
            CAPSULE_MIN_BUFFER_MS >= 120_000,
        )
        assertEquals(
            "the loader must not be allowed to drain below what it fills to",
            CAPSULE_MIN_BUFFER_MS,
            CAPSULE_MAX_BUFFER_MS,
        )
    }

    /**
     * The byte limit has to allow the duration limit, or raising the duration changes nothing.
     *
     * Media3 stops at whichever limit it meets first, and its own audio-only default is 832 kB --
     * about fifty seconds at this bitrate, which is exactly the ceiling that needed lifting.
     */
    @Test
    fun theByteLimitDoesNotCapTheDurationLimit() {
        val bytesNeeded = bytesPerSecond.toLong() * CAPSULE_MIN_BUFFER_MS / 1000L
        assertTrue(
            "$CAPSULE_TARGET_BUFFER_BYTES bytes only holds ${CAPSULE_TARGET_BUFFER_BYTES / bytesPerSecond}s",
            CAPSULE_TARGET_BUFFER_BYTES >= bytesNeeded,
        )
    }

    /** And it stays small enough to be a buffer rather than a download. */
    @Test
    fun theBufferStaysAffordable() {
        assertTrue(
            "${CAPSULE_TARGET_BUFFER_BYTES / 1024 / 1024}MiB of audio buffer is too much to hold",
            CAPSULE_TARGET_BUFFER_BYTES <= 16 * 1024 * 1024,
        )
    }

    /** Resuming on a cushion is what turns a dozen stutters into one pause. */
    @Test
    fun resumingAfterAStallWaitsForMoreThanAMoment() {
        assertTrue(
            "resuming at ${CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS}ms stalls again immediately",
            CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >= 5_000,
        )
        assertTrue(
            "a first start must stay responsive, so it waits for far less",
            CAPSULE_BUFFER_FOR_PLAYBACK_MS < CAPSULE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
}
