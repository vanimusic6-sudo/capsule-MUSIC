from pathlib import Path

root = Path('.')

safety = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsulePlaybackSafety.kt'
safety.write_text('''/*
 * Capsule MUSIC
 * Playback safety state for the modern InnerTubeX audio boundary.
 *
 * GPL-3.0
 */
package com.nikhil.yt.playback.audio

import androidx.media3.common.PlaybackException
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Safety state for explicit YouTube rate-limit / bot-check failures.
 *
 * HTTP 429 is global immediately: rotating identities after a rate limit only makes
 * the situation worse. A bot-check is different: one playback profile can be rejected
 * while another maintained profile still works. The wire interceptor therefore records
 * the signal, the owning resolver quarantines that profile, and only a confirmed/final
 * foreground failure opens the global breaker.
 */
internal object CapsulePlaybackSafety {
    private const val TAG = "CapsulePlaybackSafety"
    private const val GLOBAL_BREAKER_MS = 10 * 60 * 1000L
    private const val PROFILE_BOT_QUARANTINE_MS = 10 * 60 * 1000L

    @Volatile
    private var breakerUntilMs: Long = 0L

    @Volatile
    private var breakerReason: String? = null

    private val wireBotGeneration = AtomicLong(0L)
    private val botProfileQuarantineUntilMs = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun remainingBlockMs(nowMs: Long = System.currentTimeMillis()): Long {
        val until = breakerUntilMs
        if (until <= 0L) return 0L
        if (until <= nowMs) {
            clear()
            return 0L
        }
        return until - nowMs
    }

    @Synchronized
    fun blockedExceptionOrNull(nowMs: Long = System.currentTimeMillis()): PlaybackException? {
        val until = breakerUntilMs
        if (until <= 0L) return null

        if (until <= nowMs) {
            clear()
            return null
        }

        val remainingSeconds = ((until - nowMs) / 1000L).coerceAtLeast(1L)
        return PlaybackException(
            buildString {
                append("YouTube playback is cooling down")
                breakerReason?.let {
                    append(": ")
                    append(it)
                }
                append(" (")
                append(remainingSeconds)
                append("s)")
            },
            null,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }

    /** Record a machine-readable /player bot challenge without globally stopping AUDIO. */
    fun noteWireBotCheck() {
        wireBotGeneration.incrementAndGet()
    }

    fun wireBotSignalGeneration(): Long = wireBotGeneration.get()

    /**
     * The exception can lose YouTube's playability reason while InnerTubeX summarizes
     * diagnostics. The wire generation preserves the stronger signal for this one
     * serialized profile attempt without storing response bodies or credentials.
     */
    fun classifyFailureSince(
        error: Throwable,
        wireGenerationBeforeAttempt: Long,
    ): YouTubeFailureKind =
        if (wireBotGeneration.get() != wireGenerationBeforeAttempt) {
            YouTubeFailureKind.BOT_CHECK
        } else {
            classifyFailure(error)
        }

    fun classifyFailure(error: Throwable): YouTubeFailureKind {
        val text = throwableText(error)
        val classified = YouTubeFailureClassifier.classify(text = text)
        if (classified != YouTubeFailureKind.NONE) return classified

        return if (Regex("(^|\\D)429(\\D|$)").containsMatchIn(text)) {
            YouTubeFailureKind.RATE_LIMITED
        } else {
            YouTubeFailureKind.NONE
        }
    }

    /** Only rate limiting is global at this layer; bot escalation is profile-aware. */
    fun observeFailure(error: Throwable) {
        if (classifyFailure(error) == YouTubeFailureKind.RATE_LIMITED) {
            markRateLimited("YouTube returned HTTP 429")
        }
    }

    fun markHttpStatusFailure(httpStatusCode: Int?, reason: String? = null) {
        if (httpStatusCode == 429) {
            markRateLimited(reason ?: "YouTube returned HTTP 429")
        }
    }

    fun markProfileBotCheck(
        profileId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalized = normalizeProfileId(profileId)
        if (normalized.isBlank()) return
        botProfileQuarantineUntilMs[normalized] = nowMs + PROFILE_BOT_QUARANTINE_MS
        Timber.tag(TAG).w(
            "AUDIO profile quarantined after bot-check profile=%s durationMs=%d",
            normalized,
            PROFILE_BOT_QUARANTINE_MS,
        )
    }

    fun quarantinedProfileIds(nowMs: Long = System.currentTimeMillis()): Set<String> {
        botProfileQuarantineUntilMs.forEach { (profileId, untilMs) ->
            if (untilMs <= nowMs) {
                botProfileQuarantineUntilMs.remove(profileId, untilMs)
            }
        }
        return botProfileQuarantineUntilMs.keys.toSet()
    }

    fun markBotDetectionFailure(reason: String? = null) {
        val cleanReason =
            reason
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(160)

        trip(
            cleanReason?.let { "YouTube bot-check: $it" }
                ?: "YouTube requested a bot check",
        )
    }

    fun isBotDetectionException(error: PlaybackException): Boolean =
        classifyFailure(error) == YouTubeFailureKind.BOT_CHECK

    fun isRateLimitedException(error: Throwable): Boolean =
        classifyFailure(error) == YouTubeFailureKind.RATE_LIMITED

    @Synchronized
    fun clear() {
        breakerUntilMs = 0L
        breakerReason = null
        botProfileQuarantineUntilMs.clear()
    }

    private fun markRateLimited(reason: String) {
        trip(reason)
    }

    @Synchronized
    private fun trip(reason: String) {
        if (breakerUntilMs > System.currentTimeMillis()) return
        val until = System.currentTimeMillis() + GLOBAL_BREAKER_MS
        if (until > breakerUntilMs) breakerUntilMs = until
        breakerReason = reason

        Timber.tag(TAG).w(
            "Global AUDIO breaker opened for %d ms: %s",
            GLOBAL_BREAKER_MS,
            reason,
        )
    }

    private fun normalizeProfileId(profileId: String): String =
        profileId.substringBefore('@').trim().uppercase(Locale.US)

    private fun throwableText(error: Throwable): String =
        generateSequence(error as Throwable?) { it?.cause }
            .take(8)
            .mapNotNull { it?.message }
            .joinToString(" ")
}
''', encoding='utf-8')

policy = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicy.kt'
policy.write_text('''package com.nikhil.yt.playback.audio

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

    private const val MAX_FOREGROUND_ATTEMPTS = 4

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
''', encoding='utf-8')

interceptor = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioRequestInterceptor.kt'
text = interceptor.read_text(encoding='utf-8')
old = '''        when (signal) {
            YouTubeFailureKind.RATE_LIMITED -> CapsulePlaybackSafety.markHttpStatusFailure(429)
            YouTubeFailureKind.BOT_CHECK -> CapsulePlaybackSafety.markBotDetectionFailure()
            else -> Unit
        }
        // Return the original, unconsumed response. Every nested library retry passes the
        // gate above, so a challenge permits no further HTTP requests, even if its reason
        // is later converted to NO_PLAYABLE_STREAM by the pinned library.
        return response
'''
new = '''        when (signal) {
            YouTubeFailureKind.RATE_LIMITED -> CapsulePlaybackSafety.markHttpStatusFailure(429)
            // Preserve the challenge even if InnerTubeX later summarizes it as NO_PLAYABLE_STREAM.
            // The serialized resolver knows which profile caused it and owns safe escalation.
            YouTubeFailureKind.BOT_CHECK -> CapsulePlaybackSafety.noteWireBotCheck()
            else -> Unit
        }
        // HTTP 429 has already opened the global gate. Bot-check escalation is profile-aware.
        return response
'''
if old not in text:
    raise SystemExit('CapsuleAudioRequestInterceptor anchor not found')
interceptor.write_text(text.replace(old, new, 1), encoding='utf-8')

player = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt'
text = player.read_text(encoding='utf-8')
if 'import com.nikhil.yt.innertube.YouTubeFailureKind\n' not in text:
    text = text.replace(
        'import com.nikhil.yt.innertube.YouTube\n',
        'import com.nikhil.yt.innertube.YouTube\nimport com.nikhil.yt.innertube.YouTubeFailureKind\n',
        1,
    )
start = text.index('    suspend fun playerResponseForPlayback(')
end = text.index('    private suspend fun extractDirectStream(', start)
replacement = '''    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        streamPolicy: AudioStreamPolicy,
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
    ): Result<PlaybackData> =
        try {
            val primaryProfileId = streamPolicy.playbackClientOverrideId
            val baseHints =
                ContentHints(
                    isUploaded = playlistId == "MLPT" || playlistId?.contains("MLPT") == true,
                    wantVideo = false,
                    playbackClientOverrideId = null,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    /* Capsule Media3 does not yet consume InnerTubeX chunk scheduling. */
                    allowBoundedRange = false,
                )

            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val stream =
                scheduler.run(videoId, priority) {
                    resolveMutex.withLock {
                        withTimeout(ENGINE_RESOLVE_TIMEOUT_MS) {
                            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
                            val extractionBundle = bundle()
                            Timber.tag(TAG).i(
                                "Resolving audio id=%s priority=%s primaryProfile=%s",
                                videoId,
                                priority,
                                primaryProfileId,
                            )
                            resolveWithSafeClientFallbacks(
                                extractionBundle = extractionBundle,
                                videoId = videoId,
                                baseHints = baseHints,
                                audioQuality = resolvedQuality,
                                primaryProfileId = primaryProfileId,
                                priority = priority,
                            )
                        }
                    }
                }

            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            Result.success(stream.toPlaybackData())
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w(
                timeout,
                "engine resolve timeout id=%s priority=%s budgetMs=%d",
                videoId,
                priority,
                ENGINE_RESOLVE_TIMEOUT_MS,
            )
            Result.failure(
                SocketTimeoutException(
                    "InnerTubeX audio resolve exceeded ${ENGINE_RESOLVE_TIMEOUT_MS} ms",
                ).apply { initCause(timeout) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            Result.failure(
                CapsulePlaybackSafety.blockedExceptionOrNull() ?: if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(CapsulePlaybackSafety.blockedExceptionOrNull() ?: error)
        }

    /**
     * Metrolist-style client resilience with a Capsule-sized request budget.
     * Background work never rotates clients. Foreground playback uses a short vetted chain;
     * after a bot-check exactly one different family may be tried before escalation.
     */
    private suspend fun resolveWithSafeClientFallbacks(
        extractionBundle: ExtractionBundle,
        videoId: String,
        baseHints: ContentHints,
        audioQuality: InnerTubeXAudioQuality,
        primaryProfileId: String,
        priority: AudioResolvePriority,
    ): ExtractedStream {
        val quarantinedAtStart = CapsulePlaybackSafety.quarantinedProfileIds()
        val perSongExcluded = failedStreamClients(videoId)
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = primaryProfileId,
                priority = priority,
                authenticated = extractionBundle.innerTube.hasSapCookieAuth(),
                isUploaded = baseHints.isUploaded == true,
                excludedProfiles = quarantinedAtStart + perSongExcluded,
            )

        if (plan.isEmpty()) {
            throw IllegalStateException(
                if (priority == AudioResolvePriority.PLAYBACK) {
                    "No safe AUDIO client profile is currently available"
                } else {
                    "AUDIO background resolve suppressed while its primary profile is quarantined"
                },
            )
        }

        var lastFailure: Exception? = null
        var botSignalAlreadySeen = quarantinedAtStart.isNotEmpty()
        var onlyPostBotProfile: String? = null

        for (profileId in plan) {
            if (onlyPostBotProfile != null && profileId != onlyPostBotProfile) continue
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }

            val wireGeneration = CapsulePlaybackSafety.wireBotSignalGeneration()
            Timber.tag(TAG).i(
                "AUDIO profile attempt id=%s priority=%s profile=%s",
                videoId,
                priority,
                profileId,
            )

            try {
                val stream =
                    extractDirectStream(
                        extractionBundle = extractionBundle,
                        videoId = videoId,
                        hints = baseHints.copy(playbackClientOverrideId = profileId),
                        audioQuality = audioQuality,
                    )
                if (profileId != primaryProfileId) {
                    Timber.tag(TAG).i(
                        "AUDIO fallback recovered id=%s primary=%s selected=%s",
                        videoId,
                        primaryProfileId,
                        profileId,
                    )
                }
                return stream
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val kind =
                    CapsulePlaybackSafety.classifyFailureSince(
                        error = failure,
                        wireGenerationBeforeAttempt = wireGeneration,
                    )

                if (kind == YouTubeFailureKind.RATE_LIMITED) {
                    CapsulePlaybackSafety.observeFailure(failure)
                    throw failure
                }

                if (kind == YouTubeFailureKind.BOT_CHECK) {
                    CapsulePlaybackSafety.markProfileBotCheck(profileId)
                    markStreamClientFailed(videoId, profileId)
                    Timber.tag(TAG).w(
                        "AUDIO bot-check isolated id=%s priority=%s profile=%s",
                        videoId,
                        priority,
                        profileId,
                    )

                    if (priority != AudioResolvePriority.PLAYBACK) throw failure

                    if (botSignalAlreadySeen) {
                        CapsulePlaybackSafety.markBotDetectionFailure(
                            "confirmed across multiple AUDIO client profiles",
                        )
                        throw failure
                    }

                    val crossFamily =
                        CapsuleAudioFallbackPolicy.crossFamilyFallback(plan, profileId)
                            ?: throw failure
                    botSignalAlreadySeen = true
                    onlyPostBotProfile = crossFamily
                    lastFailure = failure
                    continue
                }

                val canFallback = CapsuleAudioFallbackPolicy.canFallbackAfter(kind)
                if (canFallback) {
                    markStreamClientFailed(videoId, profileId)
                }

                // After one bot-check, the single cross-family recovery gets one chance only.
                if (onlyPostBotProfile != null || !canFallback) throw failure

                Timber.tag(TAG).w(
                    "AUDIO client-local failure id=%s profile=%s kind=%s; trying bounded fallback",
                    videoId,
                    profileId,
                    kind,
                )
                lastFailure = failure
            }
        }

        throw lastFailure ?: IllegalStateException("No safe AUDIO client profile produced a stream")
    }

'''
text = text[:start] + replacement + text[end:]
player.write_text(text, encoding='utf-8')

safety_test = root / 'app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsulePlaybackSafetyTest.kt'
safety_test.write_text('''package com.nikhil.yt.playback.audio

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
    fun profileBotCheckQuarantinesOnlyProfile() {
        CapsulePlaybackSafety.markProfileBotCheck("web_remix")

        assertEquals(setOf("WEB_REMIX"), CapsulePlaybackSafety.quarantinedProfileIds())
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
''', encoding='utf-8')

fallback_test = root / 'app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicyTest.kt'
fallback_test.write_text('''package com.nikhil.yt.playback.audio

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
''', encoding='utf-8')
