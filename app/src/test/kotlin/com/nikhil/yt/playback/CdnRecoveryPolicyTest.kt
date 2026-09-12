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
}
