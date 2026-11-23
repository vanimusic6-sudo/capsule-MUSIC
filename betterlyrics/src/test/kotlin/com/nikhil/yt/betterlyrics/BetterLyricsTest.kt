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
import org.junit.Assert.*
import org.junit.Test

class BetterLyricsTest {
    @Test fun cancellationIsNeverConvertedIntoANegativeCache() = runTest {
        val started = CompletableDeferred<Unit>()
        val session = BetterLyricsSession()
        val client = HttpClient(MockEngine {
            started.complete(Unit)
            awaitCancellation()
        })
        try {
            var propagated = false
            val job = launch {
                try { BetterLyrics.getLyrics("Cancelled song", "Artist", null, 180, client, session) }
                catch (c: CancellationException) { propagated = true; throw c }
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(propagated)
        } finally { client.close() }
    }

    @Test fun aSingle401DoesNotSilenceOtherCachedSongs() = runTest {
        var clock = 0L
        var requests = 0
        val session = BetterLyricsSession(nowMs = { clock })
        val client = HttpClient(MockEngine { request ->
            requests++
            if (request.url.parameters["s"] == "Cached song") {
                respond("""{"ttml":"<tt>cached lyrics</tt>"}""")
            } else respond("API key required", HttpStatusCode.Unauthorized)
        })
        try {
            assertTrue(BetterLyrics.getLyrics("Uncached song", "Artist", null, 180, client, session).isFailure)
            assertTrue(BetterLyrics.getLyrics("Uncached song", "Artist", null, 180, client, session).isFailure)
            assertEquals(1, requests)
            assertEquals("<tt>cached lyrics</tt>",
                BetterLyrics.getLyrics("Cached song", "Artist", null, 180, client, session).getOrThrow())
            assertEquals(2, requests)
            assertTrue(BetterLyrics.getLyrics("Different miss", "Artist", null, 180, client, session).isFailure)
            assertEquals(3, requests)
            clock = BetterLyricsSession.AUTH_MISS_TTL_MS
            assertTrue(BetterLyrics.getLyrics("Uncached song", "Artist", null, 180, client, session).isFailure)
            assertEquals(4, requests)
        } finally { client.close() }
    }

    @Test fun overlappingDifferentSongsStillUseBoundedSequentialRequests() = runTest {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val respondNow = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            requests++
            started.complete(Unit)
            respondNow.await()
            respond("API key required", HttpStatusCode.Unauthorized)
        })
        try {
            val session = BetterLyricsSession()
            val one = async { BetterLyrics.getLyrics("First", "Artist", null, 180, client, session) }
            started.await()
            val two = async { BetterLyrics.getLyrics("Second", "Artist", null, 180, client, session) }
            respondNow.complete(Unit)
            assertTrue(one.await().isFailure)
            assertTrue(two.await().isFailure)
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun temporary503DoesNotPoisonSubsequentQueries() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("Temporary failure", HttpStatusCode.ServiceUnavailable)
            else respond("""{"ttml":"<tt>lyrics</tt>"}""")
        })
        try {
            val session = BetterLyricsSession()
            assertTrue(BetterLyrics.getLyrics("First", "Artist", null, 180, client, session).isFailure)
            assertTrue(BetterLyrics.getLyrics("Second", "Artist", null, 180, client, session).isSuccess)
            assertEquals(2, requests)
        } finally { client.close() }
    }
}
