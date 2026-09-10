package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleAudioFallbackPolicyTest {
    @Test
    fun signedOutWebPlaybackUsesOnlyVettedBoundedFallbacks() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = emptySet(),
            )

        assertEquals(
            listOf(
                "WEB_REMIX",
                "VISIONOS_0_1",
                "WEB_EMBEDDED_PLAYER",
                "TVHTML5_SIMPLY",
            ),
            plan,
        )
        assertFalse("TVHTML5" in plan)
    }

    @Test
    fun backgroundResolveNeverRotatesProfiles() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PREFETCH,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = emptySet(),
            )
        assertEquals(listOf("WEB_REMIX"), plan)

        val quarantined =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PREFETCH,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = setOf("WEB_REMIX"),
            )
        assertTrue(quarantined.isEmpty())
    }

    @Test
    fun foregroundSkipsQuarantinedPrimary() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = setOf("WEB_REMIX"),
            )

        assertEquals(
            listOf("VISIONOS_0_1", "WEB_EMBEDDED_PLAYER", "TVHTML5_SIMPLY"),
            plan,
        )
    }

    @Test
    fun botFallbackCrossesClientFamily() {
        val plan = listOf("WEB_REMIX", "WEB_CREATOR", "VISIONOS_0_1", "WEB_EMBEDDED_PLAYER")
        assertEquals(
            "VISIONOS_0_1",
            CapsuleAudioFallbackPolicy.crossFamilyFallback(plan, "WEB_REMIX"),
        )
        assertNull(
            CapsuleAudioFallbackPolicy.crossFamilyFallback(
                listOf("WEB_REMIX", "WEB_CREATOR"),
                "WEB_REMIX",
            ),
        )
    }

    @Test
    fun uploadedPlaybackAvoidsUnsupportedProfiles() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = true,
                isUploaded = true,
                excludedProfiles = emptySet(),
            )

        assertEquals(listOf("WEB_REMIX", "WEB_CREATOR"), plan)
    }

    @Test
    fun networkAndRateFailuresDoNotRotateIdentity() {
        assertFalse(CapsuleAudioFallbackPolicy.canFallbackAfter(YouTubeFailureKind.TRANSIENT))
        assertFalse(CapsuleAudioFallbackPolicy.canFallbackAfter(YouTubeFailureKind.RATE_LIMITED))
        assertFalse(CapsuleAudioFallbackPolicy.canFallbackAfter(YouTubeFailureKind.PERMANENT))
        assertTrue(CapsuleAudioFallbackPolicy.canFallbackAfter(YouTubeFailureKind.FORBIDDEN))
        assertTrue(CapsuleAudioFallbackPolicy.canFallbackAfter(YouTubeFailureKind.UNPLAYABLE))
    }
}
