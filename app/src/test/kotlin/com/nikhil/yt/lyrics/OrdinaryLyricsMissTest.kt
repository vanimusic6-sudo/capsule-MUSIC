package com.nikhil.yt.lyrics

import com.nikhil.yt.betterlyrics.LyricsUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exception ktor itself raises for [status], rather than a stand-in for it.
 *
 * What matters here is how a real provider failure is classified, and a hand-built double could
 * agree with the classifier while disagreeing with ktor.
 */
private fun ktorFailureFor(status: HttpStatusCode): Throwable {
    val client =
        HttpClient(MockEngine { respond(content = "", status = status) }) {
            expectSuccess = true
        }
    return runBlocking {
        runCatching { client.get("https://example.invalid/lyrics") }.exceptionOrNull()
    } ?: error("ktor accepted a $status response")
}

/**
 * A provider having nothing is not the app being broken.
 *
 * Two and a half minutes of playback produced nine E/ entries with full stack traces, every one a
 * provider politely reporting a miss. Somebody reading that log concluded the lyrics cooldown was
 * broken — the message it prints is the cooldown working correctly, and it never touches the
 * network. Healthy behaviour that reads as a crash is worth fixing at the source.
 */
class OrdinaryLyricsMissTest {
    @Test fun `a provider with nothing for this track is an ordinary miss`() {
        assertTrue(NoLyricsFromProvider("NetEase has no match for the track").isOrdinaryLyricsMiss())
    }

    @Test fun `a provider in its own cooldown is an ordinary miss`() {
        // This is the line that was read as a broken cooldown. It is the cooldown.
        assertTrue(
            NoLyricsFromProvider("LyricsPlus is down; not asking again yet").isOrdinaryLyricsMiss(),
        )
    }

    @Test fun `BetterLyrics keeps its own signal`() {
        assertTrue(LyricsUnavailableException().isOrdinaryLyricsMiss())
    }

    @Test fun `a 400 from the transcript endpoint is about the track, not the app`() {
        // Every instrumental and every upload without captions answers this way.
        assertTrue(ktorFailureFor(HttpStatusCode.BadRequest).isOrdinaryLyricsMiss())
    }

    @Test fun `a 404 is an ordinary miss too`() {
        assertTrue(ktorFailureFor(HttpStatusCode.NotFound).isOrdinaryLyricsMiss())
    }

    @Test fun `a server fault is not a miss and keeps its stack trace`() {
        assertFalse(ktorFailureFor(HttpStatusCode.InternalServerError).isOrdinaryLyricsMiss())
    }

    @Test fun `our own bugs are still loud`() {
        // The transcript mapper dereferences a null when the response is shaped unexpectedly.
        assertFalse(NullPointerException().isOrdinaryLyricsMiss())
        assertFalse(IllegalStateException("parse failed").isOrdinaryLyricsMiss())
    }

    @Test fun `cancellation is never a miss`() {
        // A track change cancels in-flight lyric work; the helper rethrows it as control flow.
        assertFalse(CancellationException("track changed").isOrdinaryLyricsMiss())
    }

    @Test fun `a miss carries no stack trace, because it is thrown once per track per provider`() {
        assertTrue(NoLyricsFromProvider("nothing here").stackTrace.isEmpty())
    }

    @Test fun `a miss still says why`() {
        assertTrue(
            NoLyricsFromProvider("Matched tracks had no lyrics").message
                ?.contains("no lyrics") == true,
        )
    }
}
