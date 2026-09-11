package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleLoadErrorHandlingPolicyTest {
    @Test
    fun firstAudio403GetsOneImmediateSameUrlRetry() {
        assertEquals(
            0L,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 1,
            ),
        )
    }

    @Test
    fun secondAudio403FailsFastToFreshResolveRecovery() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 2,
            ),
        )
    }

    @Test
    fun rejected410UsesTheSameBoundedRule() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 3,
            ),
        )
    }

    @Test
    fun rateLimitFailsFastForAudioWithoutSameUrlRetry() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 429,
                errorCount = 1,
            ),
        )
    }

    @Test
    fun nonAudioLoadsKeepMedia3DefaultPolicy() {
        assertNull(
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:video:track",
                httpStatusCode = 403,
                errorCount = 2,
            ),
        )
    }
}
