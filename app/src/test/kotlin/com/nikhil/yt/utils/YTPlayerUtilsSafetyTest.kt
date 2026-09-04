package com.nikhil.yt.utils

import androidx.media3.common.PlaybackException
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.innertube.YouTubeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the small part of the legacy YouTube utility that still belongs to
 * the current playback safety boundary: failure classification and the global
 * request breaker. Client selection, stream format selection and GVS headers
 * are now owned by InnerTubeX and must not be re-specified here.
 */
class YTPlayerUtilsSafetyTest {
    @Test
    fun ageRestrictedPlayabilityNeverLooksLikeBot() {
        assertEquals(
            YouTubeFailureKind.AGE_RESTRICTED,
            YTPlayerUtils.classifyPlayabilityForTest(
                status = "AGE_VERIFICATION_REQUIRED",
                reason = "Войдите, чтобы подтвердить возраст",
            ),
        )

        val error =
            PlaybackException(
                "Sign in to confirm your age",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        assertFalse(YTPlayerUtils.isBotDetectionException(error))
    }

    @Test
    fun explicitBotCheckStillLooksLikeBot() {
        val error =
            PlaybackException(
                "Sign in to confirm you're not a bot",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        assertTrue(YTPlayerUtils.isBotDetectionException(error))

        val localizedError =
            PlaybackException(
                "Войдите в аккаунт, чтобы подтвердить, что вы не бот",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        assertTrue(YTPlayerUtils.isBotDetectionException(localizedError))
    }

    @Test
    fun requestBudgetHasHardCeiling() {
        val budget =
            YTPlayerUtils.ResolveRequestBudget(
                maxPlayerRequests = 3,
                maxStreamProbes = 4,
            )

        repeat(3) { assertTrue(budget.tryConsumePlayerRequest()) }
        assertFalse(budget.tryConsumePlayerRequest())

        repeat(4) { assertTrue(budget.tryConsumeStreamProbe()) }
        assertFalse(budget.tryConsumeStreamProbe())

        assertEquals(3, budget.playerRequestsUsed)
        assertEquals(4, budget.streamProbesUsed)
    }

    @Test
    fun retiredClientPoliciesCanNeverReachPlayback() {
        val retired =
            listOf(
                AudioStreamPolicy.MWEB,
                AudioStreamPolicy.IOS,
                AudioStreamPolicy.IOS_MUSIC,
                AudioStreamPolicy.TV_DOWNGRADED,
                AudioStreamPolicy.TVHTML5,
            )

        retired.forEach { policy ->
            assertFalse(policy.isUserSelectable)
            assertEquals(AudioStreamPolicy.AUTO_SAFE, policy.normalizedForPlayback())
        }
    }

    @Test
    fun reviewedClientPoliciesRemainSelectable() {
        val reviewed =
            listOf(
                AudioStreamPolicy.AUTO_SAFE,
                AudioStreamPolicy.VISIONOS,
                AudioStreamPolicy.WEB_EMBEDDED,
                AudioStreamPolicy.WEB,
            )

        reviewed.forEach { policy ->
            assertTrue(policy.isUserSelectable)
            assertEquals(policy, policy.normalizedForPlayback())
        }
    }
}
