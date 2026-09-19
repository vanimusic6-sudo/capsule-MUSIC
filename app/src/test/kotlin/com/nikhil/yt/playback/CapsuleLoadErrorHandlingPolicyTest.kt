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

    /**
     * A first refusal is answered at once, because time is not what cures it.
     *
     * Three captures agree: every first rejection was answered by the very next resolve, on the
     * same client and often the same CDN host, and the server never said why — an empty body and
     * its own name in the only header. Waiting out the backoff before asking for another link cost
     * the whole delay and bought nothing.
     */
    @Test
    fun aFirstRejectedSignedUrlIsReplacedAtOnce() {
        assertEquals(
            FIRST_SIGNED_URL_REJECTION_DELAY_MS,
            signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L, rejectionCount = 1),
        )
        assertEquals(
            FIRST_SIGNED_URL_REJECTION_DELAY_MS,
            signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L, rejectionCount = 1),
        )
    }

    /** Never longer than the budget itself, or the shortcut would become a delay of its own. */
    @Test
    fun theImmediateAnswerNeverOutlastsTheBudgetItShortens() {
        assertEquals(
            50L,
            signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 50L, rejectionCount = 1),
        )
    }

    /**
     * From the second refusal onwards the backoff applies unchanged.
     *
     * That is what stops a link refused over and over from turning into a storm of requests, and it
     * is the reason the shortcut is scoped to the first rejection rather than to the status code.
     */
    @Test
    fun rejectedSignedUrlPreservesSharedRecoveryBackoff() {
        assertEquals(
            1_500L,
            signedUrlRefreshDelayMs(httpStatusCode = 403, budgetDelayMs = 1_500L, rejectionCount = 2),
        )
        assertEquals(
            3_000L,
            signedUrlRefreshDelayMs(httpStatusCode = 410, budgetDelayMs = 3_000L, rejectionCount = 3),
        )
        assertEquals(
            "a status that is not a refused link keeps the backoff whatever the count",
            1_500L,
            signedUrlRefreshDelayMs(httpStatusCode = 500, budgetDelayMs = 1_500L, rejectionCount = 1),
        )
    }

    @Test
    fun repeatedSignedUrlRejectionRefreshesCipherConfigOnlyOnSecondFreshAttempt() {
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 1_500L,
            ),
        )
        assertTrue(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = 3_000L,
            ),
        )
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
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
