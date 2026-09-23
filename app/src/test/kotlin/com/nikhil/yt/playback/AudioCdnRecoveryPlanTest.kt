package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCdnRecoveryPlanTest {
    @Test
    fun transportFailureReconnectsSameUrlOnceThenChangesClientThenSkips() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("track", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("track", false))
        assertTrue(plan.nextClientAlreadyTried("track"))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("track", false))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("track", false))
    }

    @Test
    fun noByteCdnOpenTimeoutGoesDirectlyToNextClientWithoutRepeatingFifteenSecondWait() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(
            AudioCdnRecoveryAction.TRY_NEXT_CLIENT,
            plan.onFailure("song", signedUrlRejected = false, unresponsiveOpen = true),
        )
        assertTrue(plan.nextClientAlreadyTried("song"))
        assertEquals(
            AudioCdnRecoveryAction.SKIP_TRACK,
            plan.onFailure("song", signedUrlRejected = false, unresponsiveOpen = true),
        )
    }

    @Test
    fun aReadTimeoutOnAnOpenedStreamStillGetsOneSameLinkReconnect() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(
            AudioCdnRecoveryAction.RETRY_SAME_URL,
            plan.onFailure("song", signedUrlRejected = false, unresponsiveOpen = false),
        )
        assertEquals(
            AudioCdnRecoveryAction.TRY_NEXT_CLIENT,
            plan.onFailure("song", signedUrlRejected = false, unresponsiveOpen = true),
        )
    }

    @Test
    fun rejectedSignedUrlAlreadyRetriedInChunkLayerSoNextClientComesFirst() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("track", true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("track", true))
    }

    @Test
    fun mixedTransportAndRejectionDoesNotRestartFailedClient() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("track", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("track", true))
        assertEquals(AudioCdnRecoveryAction.SKIP_TRACK, plan.onFailure("track", false))
    }

    @Test
    fun distinctSongsKeepSeparateBoundedPlansAndHealthyResetClearsState() {
        val plan = AudioCdnRecoveryPlan()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("b", false))
        assertFalse(plan.nextClientAlreadyTried("a"))
        plan.reset("a")
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("a", false))
        assertEquals(AudioCdnRecoveryAction.TRY_NEXT_CLIENT, plan.onFailure("b", false))
        plan.clear()
        assertEquals(AudioCdnRecoveryAction.RETRY_SAME_URL, plan.onFailure("b", false))
    }
}
