package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCdnRecoveryPlanTest {
    @Test fun signedUrlRefusalTriesDirectThenHybridBeforeStopping() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", true))
        assertTrue(plan.nextClientAlreadyTried("a"))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", false))
    }

    @Test fun noByteOpenTimeoutDoesNotReopenTheSameHost() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT,
            plan.onFailure("a", signedUrlRejected = false, unresponsiveOpen = true))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", false))
    }

    @Test fun onlyTransientTransportFailureGetsOneReconnect() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertFalse(plan.nextClientAlreadyTried("a"))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", false))
    }

    @Test fun afterReconnectAnHttp403MustSwitchClientNotRetry() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", false))
    }

    @Test fun failedSabrReturnsToDirectExactlyOnce() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("podcast", true))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("podcast", true))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onSabrFailure("podcast"))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onSabrFailure("podcast"))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("podcast", true))
        plan.reset("podcast")
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("podcast", false))
    }

    @Test fun tracksAndHealthyResetAreIsolated() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("a", true))
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("b", false))
        plan.reset("a")
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("b", false))
        plan.clear()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("b", false))
    }
}
