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
import kotlinx.coroutines.async
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
        val session = BetterLyricsSession()
        val client = HttpClient(MockEngine {
            started.complete(Unit)
            awaitCancellation()
        })
        BetterLyrics.logger = messages::add
        try {
            val job = launch {
                try {
                    BetterLyrics.getLyrics("Cancelled song", "Artist", null, 180, client, session)
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
    fun apiKeyRejectionBlocksNewSongsButPreservesPreviouslyFetchedLyrics() = runTest {
        var requests = 0
        val session = BetterLyricsSession()
        val client = HttpClient(MockEngine { request ->
            requests++
            if (request.url.parameters["s"] != "Cached song") {
                respond("API key required", HttpStatusCode.Unauthorized)
            } else {
                respond("""{"ttml":"<tt>cached lyrics</tt>"}""")
            }
        })
        try {
            assertEquals(
                "<tt>cached lyrics</tt>",
                BetterLyrics.getLyrics("Cached song", "Artist", null, 180, client, session).getOrThrow(),
            )
            listOf("Uncached song", "Another song", "Third song").forEach { title ->
                val result = BetterLyrics.getLyrics(title, "Artist", null, 180, client, session)
                assertTrue(result.exceptionOrNull() is LyricsUnavailableException)
            }
            assertEquals(2, requests)
            val available = BetterLyrics.getLyrics("Cached song", "Artist", null, 180, client, session)
            assertEquals("<tt>cached lyrics</tt>", available.getOrThrow())
            assertEquals(2, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun concurrentSongsObserveTheFirstApiKeyRejectionBeforeSendingMoreRequests() = runTest {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val respondNow = CompletableDeferred<Unit>()
        val session = BetterLyricsSession()
        val client = HttpClient(MockEngine {
            requests++
            started.complete(Unit)
            respondNow.await()
            respond("API key required", HttpStatusCode.Unauthorized)
        })
        try {
            val first = async { BetterLyrics.getLyrics("First", "Artist", null, 180, client, session) }
            started.await()
            val second = async { BetterLyrics.getLyrics("Second", "Artist", null, 180, client, session) }
            respondNow.complete(Unit)
            assertTrue(first.await().isFailure)
            assertTrue(second.await().isFailure)
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun ordinaryHttpFailureDoesNotDisableTheProvider() = runTest {
        var requests = 0
        val session = BetterLyricsSession()
        val client = HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("Temporary failure", HttpStatusCode.ServiceUnavailable)
            else respond("""{"ttml":"<tt>lyrics</tt>"}""")
        })
        try {
            assertTrue(BetterLyrics.getLyrics("First", "Artist", null, 180, client, session).isFailure)
            assertTrue(BetterLyrics.getLyrics("Second", "Artist", null, 180, client, session).isSuccess)
            assertEquals(2, requests)
        } finally {
            client.close()
        }
    }
}
