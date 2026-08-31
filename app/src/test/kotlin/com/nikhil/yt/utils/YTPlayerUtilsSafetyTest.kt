package com.nikhil.yt.utils

import androidx.media3.common.PlaybackException
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun autoMeteredPrefersHighButNotHugeBitrate() {
        val result =
            YTPlayerUtils.selectAudioFormatCandidatesForTest(
                formats =
                    listOf(
                        format(itag = 1, bitrate = 128_000, codec = "opus"),
                        format(itag = 2, bitrate = 256_000, codec = "opus"),
                    ),
                audioQuality = AudioQuality.AUTO,
                networkMetered = true,
            )

        assertEquals(128_000, result.first().bitrate)
    }

    @Test
    fun avoidCodecsActuallyFiltersThem() {
        val result =
            YTPlayerUtils.selectAudioFormatCandidatesForTest(
                formats =
                    listOf(
                        format(itag = 1, bitrate = 256_000, codec = "opus"),
                        format(itag = 2, bitrate = 160_000, codec = "mp4a.40.2"),
                    ),
                audioQuality = AudioQuality.HIGHEST,
                networkMetered = false,
                avoidCodecs = setOf("opus"),
            )

        assertEquals(2, result.first().itag)
    }

    @Test
    fun loginAndAgeCanTryNextReviewedClient() {
        assertTrue(
            YTPlayerUtils.shouldTryNextClientForTest(
                YouTubeFailureKind.LOGIN_REQUIRED,
            ),
        )
        assertTrue(
            YTPlayerUtils.shouldTryNextClientForTest(
                YouTubeFailureKind.AGE_RESTRICTED,
            ),
        )
        assertFalse(
            YTPlayerUtils.shouldTryNextClientForTest(
                YouTubeFailureKind.PERMANENT,
            ),
        )
    }


    private fun format(
        itag: Int,
        bitrate: Int,
        codec: String,
    ): PlayerResponse.StreamingData.Format =
        PlayerResponse.StreamingData.Format(
            itag = itag,
            url = "https://example.invalid/$itag",
            mimeType = "audio/webm; codecs=\"$codec\"",
            bitrate = bitrate,
            width = null,
            height = null,
            contentLength = 1_000_000L,
            quality = "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrate,
            audioQuality = "AUDIO_QUALITY_HIGH",
            approxDurationMs = "180000",
            audioSampleRate = 48_000,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
}
