package com.nikhil.yt.lyrics

import com.nikhil.yt.betterlyrics.LyricsUnavailableException
import io.ktor.client.plugins.ResponseException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A provider has nothing for this track. An ordinary answer, not a failure of the app.
 *
 * Most tracks have no lyrics at some provider or other, so this is the common case rather than
 * the exceptional one, and it was being reported as an application error: a capture of two and a
 * half minutes carried nine E/ entries with full stack traces, every one of them a provider
 * politely saying it had nothing, or saying it was in its own cooldown and would not be asked
 * again yet. Reading that log, the healthy behaviour was indistinguishable from a crash.
 *
 * The stack trace is dropped deliberately. This is control flow — thrown once per provider per
 * track — and the place it was thrown from says nothing that the message does not.
 */
class NoLyricsFromProvider(message: String) : IllegalStateException(message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Whether a provider's failure is it having nothing, rather than something being wrong here.
 *
 * Covers this app's providers, BetterLyrics' own signal, and any 4xx from a lyrics endpoint: a
 * lyrics service answering "400" or "404" is telling us about the track, not about the app. The
 * YouTube transcript endpoint does exactly that for every instrumental and every upload without
 * captions, and each one was arriving as an error with the response body attached.
 *
 * Deliberately not covered: 5xx, parse failures, and anything thrown by our own mapping code.
 * Those are worth a stack trace, and keeping them loud is the reason for drawing the line here
 * rather than treating every provider failure as routine.
 */
internal fun Throwable.isOrdinaryLyricsMiss(): Boolean =
    when {
        this is CancellationException -> false
        this is NoLyricsFromProvider -> true
        this is LyricsUnavailableException -> true
        this is ResponseException -> response.status.value in 400..499
        else -> false
    }
