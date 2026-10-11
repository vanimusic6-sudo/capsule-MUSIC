package com.nikhil.yt.innertube.video

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleNewPipeExtractorTest {
    @Test
    fun http429TextIsRateLimitedNotCaptcha() {
        assertEquals(
            CapsuleNewPipeFailure.RATE_LIMITED,
            CapsuleNewPipeExtractor.classify(
                IllegalStateException("YouTube returned HTTP 429"),
            ),
        )
    }

    @Test
    fun ageRequirementIsUnavailableNotBot() {
        assertEquals(
            CapsuleNewPipeFailure.UNAVAILABLE,
            CapsuleNewPipeExtractor.classify(
                IllegalStateException("Sign in to confirm your age"),
            ),
        )
    }

    @Test
    fun loginRequiredWithLiteralNotBotIsVideoBotBlock() {
        assertEquals(
            CapsuleNewPipeFailure.BOT_BLOCKED,
            CapsuleNewPipeExtractor.classify(IllegalStateException(
                """YouTube probably temporarily blocked anonymous watch access with this IP, got error LOGIN_REQUIRED: "Sign in to confirm that you're not a bot" """,
            )),
        )
    }

    @Test
    fun explicitBotTextIsBotBlocked() {
        assertEquals(
            CapsuleNewPipeFailure.BOT_BLOCKED,
            CapsuleNewPipeExtractor.classify(
                IllegalStateException("Confirm you're not a bot"),
            ),
        )
    }
}
