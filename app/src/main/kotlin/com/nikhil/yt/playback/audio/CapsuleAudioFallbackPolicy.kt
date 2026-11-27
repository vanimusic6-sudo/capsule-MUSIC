package com.nikhil.yt.playback.audio

import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.innertube.YouTubeFailureKind
import java.util.Locale
import java.net.UnknownHostException
import java.net.NoRouteToHostException

/**
 * Small, bounded client plan layered on top of InnerTubeX.
 *
 * User order controls foreground priority, while Capsule still owns the safety envelope:
 * speculative prefetch never rotates identities, quarantined profiles are skipped, authenticated
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
    const val MAX_RESOLVE_ATTEMPTS = 2
    private const val NORMAL_FALLBACK_DELAY_MS = 0L
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

        // Speculative prefetch never rotates identities. An explicit download may
        // use the bounded compatible plan after a client-local failure, like playback.
        if (priority == AudioResolvePriority.PREFETCH) {
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

        // Keep exactly the user's configured order. The extractor may use
        // WEB_CREATOR out of turn only after an authenticated entitlement error.
        val eligible =
            baseOrder
                .filter { it.isNotBlank() }
                .distinct()
                .filter { it in supportedProfiles }
                .filter { it !in excluded }
                .filter { profileEligible(it, authenticated, isUploaded) }

        if (eligible.size <= MAX_RESOLVE_ATTEMPTS) return eligible

        return eligible.take(MAX_RESOLVE_ATTEMPTS)
    }

    /** Only an authenticated entitlement failure may request CREATOR
     * without spending the ordinary three-client plan on every track. */
    fun entitlementRecoveryProfile(
        authenticated: Boolean,
        attemptedProfiles: Set<String>,
    ): String? = WEB_CREATOR.takeIf { authenticated && it !in attemptedProfiles }

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
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureKind.NONE,
            YouTubeFailureKind.TRANSIENT,
            -> true

            YouTubeFailureKind.FORBIDDEN,
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE,
            YouTubeFailureKind.RATE_LIMITED,
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureKind.PERMANENT,
            -> false
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

    private fun normalize(profileId: String): String =
        profileId.substringBefore('@').trim().uppercase(Locale.US)
}

/**
 * DNS resolution is shared transport state, not a verdict against an InnerTube profile.
 * On a VPN/default-network hand-off every client reaches the same unresolved YouTube
 * endpoint. Rotating identities only duplicates requests and wrongly quarantines
 * previously healthy profiles for five minutes.
 */
internal fun Throwable.isSharedAudioConnectivityFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .take(12)
        // NoRouteToHost is an OS routing failure, just like a failed DNS
        // resolution. Neither can be fixed by rotating YouTube identities.
        // Do not include read timeouts or resets: those may be CDN-local.
        .any { it is UnknownHostException || it is NoRouteToHostException }

/** Retained for older regression tests and historical diagnostics. */
internal fun Throwable.isSharedAudioDnsFailure(): Boolean = isSharedAudioConnectivityFailure()
