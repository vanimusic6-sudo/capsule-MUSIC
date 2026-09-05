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
import com.nikhil.yt.innertube.YouTubeRequestGuard
import com.nikhil.yt.innertube.YouTubeRequestBlockedException

/**
 * Global safety breaker for explicit YouTube rate-limit / bot-check failures.
 *
 * A bot check or HTTP 429 is not a reason to rotate through more client
 * identities. Pause new AUDIO extraction for a short cooldown and let a later
 * user/network event retry from a clean session instead.
 */
internal object CapsulePlaybackSafety {
    private val guard = YouTubeRequestGuard.shared

    fun blockedExceptionOrNull(nowMs: Long = System.currentTimeMillis()): PlaybackException? =
        guard.blockedExceptionOrNull(nowMs)?.let {
            PlaybackException(it.message, it, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

    fun observeFailure(error: Throwable) = guard.observeFailure(error)

    fun markHttpStatusFailure(httpStatusCode: Int?, reason: String? = null) {
        if (httpStatusCode == 429) guard.trip(reason ?: "HTTP 429")
    }

    fun markBotDetectionFailure(reason: String? = null) {
        guard.trip(reason?.trim()?.takeIf { it.isNotBlank() } ?: "YouTube requested a bot check")
    }

    fun isBotDetectionException(error: PlaybackException): Boolean =
        !isCooldownRejection(error) && YouTubeFailureClassifier.classify(text = throwableText(error)) == YouTubeFailureKind.BOT_CHECK

    fun isRateLimitedException(error: Throwable): Boolean =
        !isCooldownRejection(error) && YouTubeFailureClassifier.classify(text = throwableText(error)) == YouTubeFailureKind.RATE_LIMITED

    private fun isCooldownRejection(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is YouTubeRequestBlockedException }

    fun clear() = guard.clear()

    private fun throwableText(error: Throwable): String =
        generateSequence(error) { it.cause }.take(8).mapNotNull { it.message }.joinToString(" ")
}
