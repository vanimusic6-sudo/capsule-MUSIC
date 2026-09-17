package com.nikhil.yt.playback.audio

import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.innertube.YouTubeFailureKind
import java.util.Locale

/**
 * Small, bounded client plan layered on top of InnerTubeX.
 *
 * User order controls foreground priority, while Capsule still owns the safety envelope:
 * background work never rotates identities, quarantined families are skipped, authenticated
 * profiles are filtered when unavailable, and bot recovery may cross client families once.
 */
internal object CapsuleAudioFallbackPolicy {
    const val VISIONOS = AudioClientOrder.VISIONOS
    const val VISIONOS_0_1 = AudioClientOrder.VISIONOS_0_1
    const val WEB_REMIX = AudioClientOrder.WEB_REMIX
    const val WEB_EMBEDDED = AudioClientOrder.WEB_EMBEDDED
    const val WEB_CREATOR = AudioClientOrder.WEB_CREATOR
    const val TVHTML5_SIMPLY = AudioClientOrder.TVHTML5_SIMPLY

    private val supportedProfiles = AudioClientOrder.supportedProfiles.toSet()
    private const val MAX_FOREGROUND_ATTEMPTS = 3
    private const val NORMAL_FALLBACK_DELAY_MS = 350L
    private const val BOT_FALLBACK_DELAY_MS = 1_000L

    fun profilePlan(
        primaryProfileId: String,
        priority: AudioResolvePriority,
        authenticated: Boolean,
        isUploaded: Boolean,
        excludedProfiles: Set<String>,
        preferredProfiles: List<String> = emptyList(),
    ): List<String> {
        val primary = normalize(primaryProfileId)
        val excluded = excludedProfiles.map(::normalize).toSet()
        val configured =
            preferredProfiles
                .map(::normalize)
                .filter { it in supportedProfiles }
                .distinct()

        // PREFETCH/DOWNLOAD never walk down the user list after a failure. They use only
        // position #1. If that profile cannot safely run right now, background work is skipped.
        if (priority != AudioResolvePriority.PLAYBACK) {
            val first = configured.firstOrNull() ?: primary
            return listOf(first)
                .filter { it.isNotBlank() }
                .filter { it !in excluded }
                .filter { profileEligible(it, authenticated, isUploaded) }
        }

        val baseOrder =
            if (configured.isNotEmpty()) {
                configured
            } else {
                buildList {
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
                    add(TVHTML5_SIMPLY)
                }
            }

        val ordered = preferAuthenticatedWebFallback(baseOrder, authenticated)

        val eligible =
            ordered
                .filter { it.isNotBlank() }
                .distinct()
                .filter { it in supportedProfiles }
                .filter { it !in excluded }
                .filter { profileEligible(it, authenticated, isUploaded) }

        if (eligible.size <= MAX_FOREGROUND_ATTEMPTS) return eligible

        val firstWindow = eligible.take(MAX_FOREGROUND_ATTEMPTS)
        if (!authenticated || WEB_CREATOR !in eligible || WEB_CREATOR in firstWindow) {
            return firstWindow
        }

        // Keep the request budget at three identities, but do not accidentally remove the
        // only maintained authenticated music profile. This preserves restricted/uploaded
        // playback without restoring the old six-client waterfall.
        return eligible.take(MAX_FOREGROUND_ATTEMPTS - 1) + WEB_CREATOR
    }

    /**
     * WEB_REMIX and WEB_CREATOR share the authenticated web-music family, but CREATOR keeps
     * an account-backed player path that can still satisfy login/age gates when REMIX is
     * temporarily rejected at the GVS layer. Put it immediately after REMIX for signed-in
     * playback so a 403 never burns the next request on an anonymous identity first.
     *
     * A manual client order is allowed to omit WEB_CREATOR from the visible preference list;
     * that must not silently remove Capsule's only authenticated age-gate recovery path. When
     * signed in and REMIX is present, CREATOR is therefore injected as a bounded recovery hop.
     * Signed-out playback and orders without WEB_REMIX are left untouched.
     */
    private fun preferAuthenticatedWebFallback(
        order: List<String>,
        authenticated: Boolean,
    ): List<String> {
        if (!authenticated) return order
        val remixIndex = order.indexOf(WEB_REMIX)
        if (remixIndex < 0) return order

        val creatorIndex = order.indexOf(WEB_CREATOR)
        if (creatorIndex == remixIndex + 1) return order

        val withoutCreator = order.toMutableList()
        if (creatorIndex >= 0) {
            withoutCreator.removeAt(creatorIndex)
        }
        val newRemixIndex = withoutCreator.indexOf(WEB_REMIX)
        withoutCreator.add(newRemixIndex + 1, WEB_CREATOR)
        return withoutCreator
    }

    fun fallbackDelayMs(kind: YouTubeFailureKind): Long =
        when (kind) {
            YouTubeFailureKind.BOT_CHECK -> BOT_FALLBACK_DELAY_MS
            YouTubeFailureKind.FORBIDDEN,
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.NONE,
            -> NORMAL_FALLBACK_DELAY_MS
            else -> 0L
        }

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

    /** After a bot-check use the first later profile from a different user-ordered family. */
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

    private fun profileEligible(
        profileId: String,
        authenticated: Boolean,
        isUploaded: Boolean,
    ): Boolean {
        if (profileId == WEB_CREATOR && !authenticated) return false
        if (!isUploaded) return true
        return profileId == WEB_REMIX || (authenticated && profileId == WEB_CREATOR)
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
