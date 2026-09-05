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

/**
 * Global safety breaker for explicit YouTube rate-limit / bot-check failures.
 *
 * A bot check or HTTP 429 is not a reason to rotate through more client
 * identities. Pause new AUDIO extraction for a short cooldown and let a later
 * user/network event retry from a clean session instead.
 */
internal object CapsulePlaybackSafety {
    private const val TAG = "CapsulePlaybackSafety"
    private const val GLOBAL_BREAKER_MS = 10 * 60 * 1000L

    @Volatile
    private var breakerUntilMs: Long = 0L

    @Volatile
    private var breakerReason: String? = null

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

    fun observeFailure(error: Throwable) {
        val text = throwableText(error)
        when (YouTubeFailureClassifier.classify(text = text)) {
            YouTubeFailureKind.RATE_LIMITED -> markRateLimited("YouTube returned HTTP 429")
            YouTubeFailureKind.BOT_CHECK -> markBotDetectionFailure(text)
            else -> {
                // Some transport wrappers omit structured status metadata but
                // preserve the HTTP code in their message.
                if (Regex("(^|\\D)429(\\D|$)").containsMatchIn(text)) {
                    markRateLimited("YouTube returned HTTP 429")
                }
            }
        }
    }

    fun markHttpStatusFailure(httpStatusCode: Int?, reason: String? = null) {
        if (httpStatusCode == 429) {
            markRateLimited(reason ?: "YouTube returned HTTP 429")
        }
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
        YouTubeFailureClassifier.classify(text = throwableText(error)) ==
            YouTubeFailureKind.BOT_CHECK

    fun isRateLimitedException(error: Throwable): Boolean =
        YouTubeFailureClassifier.classify(text = throwableText(error)) == YouTubeFailureKind.RATE_LIMITED

    fun clear() {
        breakerUntilMs = 0L
        breakerReason = null
    }

    private fun markRateLimited(reason: String) {
        trip(reason)
    }

    private fun trip(reason: String) {
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
