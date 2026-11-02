package com.nikhil.yt.playback.video

import org.junit.Assert.assertTrue
import org.junit.Test

/** VIDEO has a separate Media3 buffer; audio-only policy must not mask its underruns. */
class CapsuleVideoBufferPolicyTest {
    @Test fun startupRemainsFastWhileVideoBuildsResilienceAfterFirstFrame() {
        assertTrue(CAPSULE_VIDEO_START_BUFFER_MS in 750..1_500)
        assertTrue(CAPSULE_VIDEO_MIN_BUFFER_MS >= 12_000)
        assertTrue(CAPSULE_VIDEO_MAX_BUFFER_MS >= CAPSULE_VIDEO_MIN_BUFFER_MS * 2)
    }

    @Test fun rebufferWaitsForUsefulRunwayWithoutAllocatingAnUnboundedQueue() {
        assertTrue(CAPSULE_VIDEO_REBUFFER_MS >= 5_000)
        assertTrue(CAPSULE_VIDEO_REBUFFER_MS < CAPSULE_VIDEO_MIN_BUFFER_MS)
        assertTrue(CAPSULE_VIDEO_TARGET_BUFFER_BYTES in
            8 * 1024 * 1024..32 * 1024 * 1024)
    }
}
