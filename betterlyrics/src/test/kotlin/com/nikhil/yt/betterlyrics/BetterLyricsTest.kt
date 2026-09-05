package com.nikhil.yt.betterlyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsTest {
    @Test
    fun cancellingHttpRequestPropagatesWithoutLoggingAnUnavailableError() = runTest {
        val started = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        var returnedResult = false
        var cancellationRethrown = false
        val client = HttpClient(MockEngine {
            started.complete(Unit)
            awaitCancellation()
        })
        BetterLyrics.logger = messages::add
        try {
            val job = launch {
                try {
                    BetterLyrics.getLyrics("Cancelled song", "Artist", null, 180, client)
                    returnedResult = true
                } catch (cancelled: CancellationException) {
                    cancellationRethrown = true
                    throw cancelled
                }
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(cancellationRethrown)
            assertFalse(returnedResult)
            assertTrue(messages.none { "Error fetching lyrics" in it || "Lyrics unavailable" in it })
        } finally {
            BetterLyrics.logger = null
            client.close()
        }
    }

    @Test
    fun apiKeyOnlyQueryIsAnExpectedMissAndOtherQueriesStillWork() = runTest {
        var requests = 0
        val uniqueTitle = "API key song ${System.nanoTime()}"
        val client = HttpClient(MockEngine { request ->
            requests++
            if (request.url.parameters["s"] == uniqueTitle) {
                respond("API key required", HttpStatusCode.Unauthorized)
            } else {
                respond("""{"ttml":"<tt>cached lyrics</tt>"}""")
            }
        })
        try {
            repeat(2) {
                val result = BetterLyrics.getLyrics(uniqueTitle, "Artist", null, 180, client)
                assertTrue(result.exceptionOrNull() is LyricsUnavailableException)
            }
            assertEquals(1, requests)
            val available = BetterLyrics.getLyrics("Cached song", "Artist", null, 180, client)
            assertEquals("<tt>cached lyrics</tt>", available.getOrThrow())
            assertEquals(2, requests)
        } finally {
            client.close()
        }
    }
}
