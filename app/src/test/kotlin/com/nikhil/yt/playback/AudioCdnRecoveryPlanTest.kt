package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test

class AudioCdnRecoveryPlanTest {
    @Test fun expiredUrlOrUnresponsiveOpenGetsOneFreshUrlAndNoCarousel() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_FRESH_URL_SAME_CLIENT, plan.onFailure("a", true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", true))
        assertEquals(AudioCdnRecoveryAction.TRY_FRESH_URL_SAME_CLIENT, plan.onFailure("b", false, true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("b", false))
    }
    @Test fun transientSocketFailureReconnectsExactlyOnce() {
        val plan = AudioCdnRecoveryPlan()
        assertFalse(plan.recoveryUsed("a"))
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertTrue(plan.recoveryUsed("a"))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("a", false))
        plan.reset("a")
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
    }
    @Test fun tracksAreIndependent() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("b", false))
        plan.clear()
        assertFalse(plan.recoveryUsed("a"))
        assertFalse(plan.recoveryUsed("b"))
    }
}
