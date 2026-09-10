package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleAudioFallbackPolicyTest {
    @Test
    fun legacySignedOutWebPlaybackKeepsBoundedFallbacks() {
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
    fun manualOrderDrivesForegroundResolveOrder() {
        val custom =
            listOf(
                "TVHTML5_SIMPLY",
                "WEB_REMIX",
                "VISIONOS_0_1",
                "WEB_EMBEDDED_PLAYER",
                "VISIONOS",
                "WEB_CREATOR",
            )

        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = emptySet(),
                preferredProfiles = custom,
            )

        assertEquals(custom.dropLast(1), plan)
    }

    @Test
    fun authenticatedManualOrderCanReachAllSixMaintainedProfiles() {
        val custom =
            listOf(
                "WEB_CREATOR",
                "TVHTML5_SIMPLY",
                "WEB_EMBEDDED_PLAYER",
                "VISIONOS_0_1",
                "VISIONOS",
                "WEB_REMIX",
            )

        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = true,
                isUploaded = false,
                excludedProfiles = emptySet(),
                preferredProfiles = custom,
            )

        assertEquals(custom, plan)
    }

    @Test
    fun backgroundUsesOnlyConfiguredFirstProfile() {
        val custom = listOf("WEB_REMIX", "VISIONOS_0_1", "TVHTML5_SIMPLY")
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PREFETCH,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = emptySet(),
                preferredProfiles = custom,
            )
        assertEquals(listOf("WEB_REMIX"), plan)

        val quarantined =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PREFETCH,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = setOf("WEB_REMIX"),
                preferredProfiles = custom,
            )
        assertTrue(quarantined.isEmpty())
    }

    @Test
    fun foregroundSkipsQuarantinedClientsWithoutReorderingTheRest() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = setOf("WEB_REMIX", "WEB_CREATOR"),
                preferredProfiles =
                    listOf(
                        "WEB_REMIX",
                        "WEB_CREATOR",
                        "TVHTML5_SIMPLY",
                        "VISIONOS_0_1",
                        "WEB_EMBEDDED_PLAYER",
                    ),
            )

        assertEquals(
            listOf("TVHTML5_SIMPLY", "VISIONOS_0_1", "WEB_EMBEDDED_PLAYER"),
            plan,
        )
    }

    @Test
    fun botFallbackFollowsUserOrderButCrossesFamily() {
        val plan =
            listOf(
                "WEB_REMIX",
                "WEB_CREATOR",
                "TVHTML5_SIMPLY",
                "VISIONOS_0_1",
                "WEB_EMBEDDED_PLAYER",
            )
        assertEquals(
            "TVHTML5_SIMPLY",
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
    fun uploadedPlaybackKeepsOnlyUploadCompatibleProfilesInUserOrder() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = "VISIONOS",
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = true,
                isUploaded = true,
                excludedProfiles = emptySet(),
                preferredProfiles =
                    listOf(
                        "TVHTML5_SIMPLY",
                        "WEB_CREATOR",
                        "VISIONOS",
                        "WEB_REMIX",
                    ),
            )

        assertEquals(listOf("WEB_CREATOR", "WEB_REMIX"), plan)
    }

    @Test
    fun botQuarantineCoversSiblingIdentityProfiles() {
        assertEquals(
            setOf("WEB_REMIX", "WEB_CREATOR"),
            CapsuleAudioFallbackPolicy.botQuarantineProfiles("WEB_REMIX"),
        )
        assertEquals(
            setOf("VISIONOS", "VISIONOS_0_1"),
            CapsuleAudioFallbackPolicy.botQuarantineProfiles("VISIONOS_0_1"),
        )
        assertEquals(
            setOf("TVHTML5_SIMPLY"),
            CapsuleAudioFallbackPolicy.botQuarantineProfiles("TVHTML5_SIMPLY"),
        )
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
