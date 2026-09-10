package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureKind
import java.util.Locale

/**
 * Small, bounded client plan layered on top of InnerTubeX.
 *
 * We deliberately do not expose every catalog entry. These are the profiles that the
 * pinned InnerTubeX v0.5.2 playback matrix still considers useful for automatic/direct
 * audio work. Broken Android VR / legacy TV profiles are intentionally absent.
 */
internal object CapsuleAudioFallbackPolicy {
    const val VISIONOS = "VISIONOS"
    const val VISIONOS_0_1 = "VISIONOS_0_1"
    const val WEB_REMIX = "WEB_REMIX"
    const val WEB_EMBEDDED = "WEB_EMBEDDED_PLAYER"
    const val WEB_CREATOR = "WEB_CREATOR"
    const val TVHTML5_SIMPLY = "TVHTML5_SIMPLY"

    private const val MAX_FOREGROUND_ATTEMPTS = 5

    fun profilePlan(
        primaryProfileId: String,
        priority: AudioResolvePriority,
        authenticated: Boolean,
        isUploaded: Boolean,
        excludedProfiles: Set<String>,
    ): List<String> {
        val primary = normalize(primaryProfileId)
        val excluded = excludedProfiles.map(::normalize).toSet()

        // Background work never rotates identities. If its primary was recently
        // challenged, skip the optimization and let foreground playback decide.
        if (priority != AudioResolvePriority.PLAYBACK) {
            return listOf(primary).filter { it.isNotBlank() && it !in excluded }
        }

        val ordered = buildList {
            add(primary)
            when (primary) {
                WEB_REMIX -> addAll(listOf(VISIONOS_0_1, WEB_EMBEDDED))
                VISIONOS, VISIONOS_0_1 -> addAll(listOf(WEB_REMIX, WEB_EMBEDDED))
                WEB_EMBEDDED -> addAll(listOf(VISIONOS_0_1, WEB_REMIX))
                WEB_CREATOR -> addAll(listOf(VISIONOS_0_1, WEB_EMBEDDED, WEB_REMIX))
                TVHTML5_SIMPLY -> addAll(listOf(VISIONOS_0_1, WEB_REMIX, WEB_EMBEDDED))
                else -> addAll(listOf(VISIONOS_0_1, WEB_REMIX, WEB_EMBEDDED))
            }
            if (authenticated) add(WEB_CREATOR)
            // Plain TVHTML5 is BROKEN in the v0.5.2 matrix. TVHTML5_SIMPLY is the
            // maintained TV-family candidate, so keep it as the final rare fallback.
            add(TVHTML5_SIMPLY)
        }

        return ordered
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it !in excluded }
            .filter { profile ->
                !isUploaded ||
                    profile == WEB_REMIX ||
                    (authenticated && profile == WEB_CREATOR)
            }
            .take(MAX_FOREGROUND_ATTEMPTS)
    }

    /** Network/rate/permanent failures should not trigger identity rotation. */
    fun canFallbackAfter(kind: YouTubeFailureKind): Boolean =
        when (kind) {
            YouTubeFailureKind.FORBIDDEN,
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.NONE,
            -> true

            YouTubeFailureKind.RATE_LIMITED,
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureKind.TRANSIENT,
            YouTubeFailureKind.PERMANENT,
            -> false
        }

    /**
     * A bot challenge belongs to a client identity family, not just one version/profile.
     * Do not immediately retry a sibling identity that is likely to share the same signal.
     */
    fun botQuarantineProfiles(profileId: String): Set<String> {
        val normalized = normalize(profileId)
        if (normalized.isBlank()) return emptySet()
        return when (familyOf(normalized)) {
            "VISION" -> setOf(VISIONOS, VISIONOS_0_1)
            "WEB_MUSIC" -> setOf(WEB_REMIX, WEB_CREATOR)
            "WEB_EMBEDDED" -> setOf(WEB_EMBEDDED)
            "TV" -> setOf(TVHTML5_SIMPLY)
            else -> setOf(normalized)
        }
    }
    /** After a bot-check use at most one fallback and prefer another client family. */
    fun crossFamilyFallback(
        plan: List<String>,
        failedProfileId: String,
    ): String? {
        val failed = normalize(failedProfileId)
        val failedIndex = plan.indexOfFirst { normalize(it) == failed }
        if (failedIndex < 0) return null
        val family = familyOf(failed)
        return plan
            .drop(failedIndex + 1)
            .firstOrNull { familyOf(normalize(it)) != family }
    }

    private fun familyOf(profileId: String): String =
        when (profileId) {
            VISIONOS, VISIONOS_0_1 -> "VISION"
            WEB_REMIX, WEB_CREATOR -> "WEB_MUSIC"
            WEB_EMBEDDED -> "WEB_EMBEDDED"
            TVHTML5_SIMPLY -> "TV"
            else -> profileId
        }

    private fun normalize(profileId: String): String =
        profileId.substringBefore('@').trim().uppercase(Locale.US)
}
