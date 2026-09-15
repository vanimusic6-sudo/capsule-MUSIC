package com.nikhil.yt.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.utils.failureChain
import com.nikhil.yt.utils.httpFailureStatus
import com.nikhil.yt.utils.networkFailureKind

internal enum class PlaybackFailureKind {
    AUTH_REQUIRED, AGE_RESTRICTED, ACCESS_RESTRICTED, NETWORK, BOT_CHECK, GENERIC,
}

/** Classify the original extraction/transport causes even inside Media3's generic 2000. */
internal object PlaybackFailureClassifier {
    fun classify(error: Throwable, authenticated: Boolean): PlaybackFailureKind {
        val causes = error.failureChain()
        val text = causes.mapNotNull { it.message }.joinToString(" ")
        val textKind = YouTubeFailureClassifier.classify(text = text)
        if (textKind == YouTubeFailureKind.BOT_CHECK) return PlaybackFailureKind.BOT_CHECK

        // In particular, a CDN 403/410 is not evidence of an age or login restriction.
        if (error.httpFailureStatus() != null) return PlaybackFailureKind.GENERIC
        if (error.networkFailureKind() != null) return PlaybackFailureKind.NETWORK

        val extractionFailures = causes.filterIsInstance<StreamResolveException>()
        val playabilityKinds = extractionFailures.flatMap { failure ->
            failure.diagnostics?.attempts.orEmpty().mapNotNull { attempt ->
                // Only actual /player outcomes count, never profile selection or token failures.
                attempt.outcome.takeIf { it.startsWith("playability:") }?.let { outcome ->
                    val status = outcome.removePrefix("playability:")
                    if (status == "AGE_CHECK_REQUIRED") YouTubeFailureKind.AGE_RESTRICTED
                    else YouTubeFailureClassifier.classify(playabilityStatus = status)
                }
            }
        }
        val ageRestricted = extractionFailures.any {
            it.reason == StreamResolveException.Reason.AGE_RESTRICTED
        } || YouTubeFailureKind.AGE_RESTRICTED in playabilityKinds ||
            textKind == YouTubeFailureKind.AGE_RESTRICTED
        val loginRequired = YouTubeFailureKind.LOGIN_REQUIRED in playabilityKinds ||
            textKind == YouTubeFailureKind.LOGIN_REQUIRED

        return when {
            (ageRestricted || loginRequired) && !authenticated -> PlaybackFailureKind.AUTH_REQUIRED
            ageRestricted -> PlaybackFailureKind.AGE_RESTRICTED
            loginRequired -> PlaybackFailureKind.ACCESS_RESTRICTED
            else -> PlaybackFailureKind.GENERIC
        }
    }
}
