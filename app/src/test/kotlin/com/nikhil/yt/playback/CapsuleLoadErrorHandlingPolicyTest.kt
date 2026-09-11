package com.nikhil.yt.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLoadErrorHandlingPolicyTest {
    @Test
    fun firstAudio403RejectsCurrentSignedUrlGeneration() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 1,
            ),
        )
    }

    @Test
    fun repeatedAudio403StillRejectsCurrentSignedUrlGeneration() {
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
    fun laterAudio403StillStopsSameUrlGeneration() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 403,
                errorCount = 3,
            ),
        )
    }

    @Test
    fun rejected410GetsOnlyOneShortRetry() {
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 1,
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            audioCdnRejectedRetryDelayMs(
                cacheKey = "capsule:audio:track:251:1234",
                httpStatusCode = 410,
                errorCount = 2,
            ),
        )
    }

    @Test
    fun rejectedSignedUrlPreservesSharedRecoveryBackoff() {
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertEquals(3_000L, signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L))
        assertEquals(1_500L, signedUrlRefreshDelayMs(httpStatusCode = 500, budgetDelayMs = 1_500L))
    }

    @Test
    fun repeatedSignedUrlRejectionRefreshesSessionOnlyOnSecondFreshAttempt() {
        assertFalse(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 1_500L,
            ),
        )
        assertTrue(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 3_000L,
            ),
        )
        assertFalse(
            shouldRefreshStreamSessionAfterSignedUrlRejection(
                httpStatusCode = 500,
                budgetDelayMs = 3_000L,
            ),
        )
    }

    @Test
    fun signedUrlFreshResolveLoopStopsBeforeThirdRecoverySlot() {
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 1_500L))
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 3_000L))
        assertFalse(shouldRetryRejectedSignedUrl(httpStatusCode = 403, budgetDelayMs = 6_000L))
        assertTrue(shouldRetryRejectedSignedUrl(httpStatusCode = 500, budgetDelayMs = 6_000L))
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

    @Test
    fun resolvedHttpDataSpecAudioKeyWinsOverOuterMediaId() {
        assertEquals(
            "capsule:audio:track:251:1234",
            audioCdnRetryCacheKey(
                outerCacheKey = "track",
                resolvedFailureCacheKey = "capsule:audio:track:251:1234",
            ),
        )
    }

    @Test
    fun nonAudioResolvedKeyDoesNotHijackOuterPolicy() {
        assertEquals(
            "track",
            audioCdnRetryCacheKey(
                outerCacheKey = "track",
                resolvedFailureCacheKey = "capsule:video:track",
            ),
        )
    }
}
