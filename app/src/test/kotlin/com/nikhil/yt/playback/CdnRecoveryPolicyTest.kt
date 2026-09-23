package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnRecoveryPolicyTest {
    @Test
    fun rejectedSignedUrlRefreshesCipherConfigAtBoundedRetryThreshold() {
        assertTrue(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
        assertTrue(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 410,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
    }

    @Test
    fun rejectedSignedUrlDoesNotRefreshCipherConfigBeforeThreshold() {
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS - 1L,
            ),
        )
    }

    @Test
    fun unrelatedHttpFailureNeverUsesRejectedSignedUrlCipherRefreshPath() {
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 404,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
    }

    @Test
    fun rejectedSignedUrlRetryRemainsBounded() {
        assertTrue(
            shouldRetryRejectedSignedUrl(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS,
            ),
        )
        assertFalse(
            shouldRetryRejectedSignedUrl(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS + 1L,
            ),
        )
    }

    /**
     * A rejected signed URL names one URL on one CDN node, not the client that produced it. The
     * first rejection therefore keeps the client and takes a freshly resolved URL, which lands on a
     * different node; only a second rejection, with a second independently resolved URL, makes the
     * client the likelier explanation.
     *
     * This matters because rolling over means dropping to the next maintained profile — in practice
     * giving up the PoToken-carrying one — for the rest of that song.
     */
    @Test
    fun firstSignedUrlRejectionKeepsTheClientAndRetriesAnotherNode() {
        assertFalse(shouldRollOverClientAfterSignedUrlRejection(1))
    }

    @Test
    fun aSecondRejectionForTheSameSongRetiresTheClient() {
        assertTrue(shouldRollOverClientAfterSignedUrlRejection(2))
        assertTrue(shouldRollOverClientAfterSignedUrlRejection(3))
    }

    @Test
    fun theRolloverDecisionIsNeverTakenWithoutARejection() {
        assertFalse(shouldRollOverClientAfterSignedUrlRejection(0))
        assertFalse(shouldRollOverClientAfterSignedUrlRejection(-1))
    }
}
