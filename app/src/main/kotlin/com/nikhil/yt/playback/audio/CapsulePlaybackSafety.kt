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
import java.util.concurrent.atomic.AtomicLong

/**
 * Safety state for explicit YouTube rate-limit / bot-check failures.
 *
 * HTTP 429 is the only global breaker at this layer. A /player bot challenge is
 * deliberately kept local to the serialized resolve that observed it: the resolver
 * can retire that client for the current media id and, for foreground playback, try
 * one bounded cross-family fallback. Carrying bot state across tracks made an old
 * challenge amplify a later transient rejection into a ten-minute app-wide outage.
 */
internal object CapsulePlaybackSafety {
    private const val TAG = "CapsulePlaybackSafety"
    private const val GLOBAL_BREAKER_MS = 10 * 60 * 1000L

    @Volatile
    private var breakerUntilMs: Long = 0L

    @Volatile
    private var breakerReason: String? = null

    private val wireBotGeneration = AtomicLong(0L)

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

    /** Only a real rate-limit signal is allowed to stop all audio resolves. */
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

    /**
     * Compatibility hook for the resolver. Bot-check quarantine is intentionally
     * per-media in CapsuleInnerTubeXPlayer.failedStreamClients(); never persist a
     * client-family ban here across unrelated tracks.
     */
    fun markProfileBotCheck(profileId: String) {
        Timber.tag(TAG).w(
            "AUDIO bot-check kept local to current media resolve profile=%s",
            profileId,
        )
    }

    /**
     * Kept while older resolver call sites are migrated. A previous track's bot
     * challenge must never remove a client from a new track's plan.
     */
    @Suppress("UNUSED_PARAMETER")
    fun quarantinedProfileIds(nowMs: Long = System.currentTimeMillis()): Set<String> = emptySet()

    /**
     * Exhausting the one bounded bot fallback fails only the current resolve.
     * Do not manufacture an app-wide cooldown: only an actual HTTP 429 may do that.
     */
    fun markBotDetectionFailure(reason: String? = null) {
        Timber.tag(TAG).w(
            "AUDIO bot-check exhausted local fallback; global breaker remains closed%s",
            reason?.let { ": ${it.take(160)}" }.orEmpty(),
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

    private fun throwableText(error: Throwable): String =
        generateSequence(error as Throwable?) { it?.cause }
            .take(8)
            .mapNotNull { it?.message }
            .joinToString(" ")
}
