package com.nikhil.yt.playback.audio

import androidx.media3.common.PlaybackException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CapsulePlaybackSafetyTest {
    @Before
    fun setUp() {
        CapsulePlaybackSafety.clear()
    }

    @After
    fun tearDown() {
        CapsulePlaybackSafety.clear()
    }

    @Test
    fun explicitBotCheckOpensModernSafetyClassification() {
        val error =
            PlaybackException(
                "Sign in to confirm you're not a bot",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )

        assertTrue(CapsulePlaybackSafety.isBotDetectionException(error))
    }

    @Test
    fun ageRestrictionDoesNotLookLikeBotCheck() {
        val error =
            PlaybackException(
                "Sign in to confirm your age",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )

        assertFalse(CapsulePlaybackSafety.isBotDetectionException(error))
    }

    @Test
    fun http429OpensCooldownUntilExplicitReset() {
        CapsulePlaybackSafety.markHttpStatusFailure(429)
        assertNotNull(CapsulePlaybackSafety.blockedExceptionOrNull())

        CapsulePlaybackSafety.clear()
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
    }

    @Test
    fun transport429TextAlsoOpensCooldown() {
        CapsulePlaybackSafety.observeFailure(IllegalStateException("player request failed: HTTP 429"))
        assertNotNull(CapsulePlaybackSafety.blockedExceptionOrNull())
    }
}
