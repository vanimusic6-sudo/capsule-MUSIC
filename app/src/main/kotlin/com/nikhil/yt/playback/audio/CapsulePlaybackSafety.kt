/*
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

        return if (Regex("""(^|\D)429(\D|$)""").containsMatchIn(text)) {
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
