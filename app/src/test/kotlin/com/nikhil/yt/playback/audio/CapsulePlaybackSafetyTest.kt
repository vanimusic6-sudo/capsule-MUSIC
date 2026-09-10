package com.nikhil.yt.playback.audio

import androidx.media3.common.PlaybackException
import com.nikhil.yt.innertube.YouTubeFailureKind
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun explicitBotCheckDoesNotImmediatelyOpenGlobalBreaker() {
        val error =
            PlaybackException(
                "Sign in to confirm you're not a bot",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )

        assertTrue(CapsulePlaybackSafety.isBotDetectionException(error))
        CapsulePlaybackSafety.observeFailure(error)
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
    }

    @Test
    fun wireBotSignalSurvivesOpaqueExtractorFailure() {
        val before = CapsulePlaybackSafety.wireBotSignalGeneration()
        CapsulePlaybackSafety.noteWireBotCheck()

        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            CapsulePlaybackSafety.classifyFailureSince(
                IllegalStateException("Unable to resolve stream data"),
                before,
            ),
        )
    }

    @Test
    fun profileBotCheckQuarantinesWholeIdentityFamilyOnly() {
        CapsulePlaybackSafety.markProfileBotCheck("web_remix")

        assertEquals(
            setOf("WEB_REMIX", "WEB_CREATOR"),
            CapsulePlaybackSafety.quarantinedProfileIds(),
        )
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
    }

    @Test
    fun visionBotCheckQuarantinesBothVisionProfilesWithoutGlobalBreaker() {
        CapsulePlaybackSafety.markProfileBotCheck("visionos_0_1")

        assertEquals(
            setOf("VISIONOS", "VISIONOS_0_1"),
            CapsulePlaybackSafety.quarantinedProfileIds(),
        )
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
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

    @Test
    fun remainingCooldownReportsOpenBreaker() {
        val beforeTrip = System.currentTimeMillis()
        CapsulePlaybackSafety.markHttpStatusFailure(429)

        val remainingMs = CapsulePlaybackSafety.remainingBlockMs(beforeTrip)

        assertTrue(remainingMs >= 9 * 60 * 1000L)
        assertTrue(remainingMs <= 10 * 60 * 1000L + 1_000L)
    }
}
