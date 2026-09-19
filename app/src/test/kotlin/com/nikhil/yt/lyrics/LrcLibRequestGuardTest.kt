package com.nikhil.yt.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class LrcLibRequestGuardTest {
    @Test fun unavailableIsSharedAcrossCallersUntilTtlExpires() = runTest {
        val guard = LrcLibRequestGuard(nowMs = { testScheduler.currentTime })
        var requests = 0
        // The preload and foreground helpers pass metadata, not their distinct media ids.
        suspend fun preload(): String? = guard.request("  Song  TITLE  ", "Artist\tName", 180) {
            requests++
            null
        }
        suspend fun foreground(): String? = guard.request("song title", "artist name", 180) {
            requests++
            "lyrics"
        }
        assertNull(preload())
        assertNull(foreground())
        assertEquals(1, requests)
        testScheduler.advanceTimeBy(LrcLibRequestGuard.UNAVAILABLE_TTL_MS - 1)
        assertNull(foreground())
        testScheduler.advanceTimeBy(1)
        assertEquals("lyrics", foreground())
        assertEquals(2, requests)
    }

    @Test fun durationAndMetadataBoundariesDoNotCollide() = runTest {
        val guard = LrcLibRequestGuard()
        guard.request<String>("ab", "c", 180) { null }
        assertEquals("other title", guard.request("a", "bc", 180) { "other title" })
        assertEquals("other duration", guard.request("ab", "c", 181) { "other duration" })
    }

    @Test fun successfulResultDoesNotCreateNegativeEntry() = runTest {
        val guard = LrcLibRequestGuard()
        assertEquals("first", guard.request("song", "artist", 180) { "first" })
        assertEquals("second", guard.request("song", "artist", 180) { "second" })
    }

    @Test fun server503BlocksTheProviderThenOnlyTheFailedQuery() = runTest {
        val logs = mutableListOf<String>()
        val guard = LrcLibRequestGuard({ testScheduler.currentTime }, logs::add)
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("{\"name\":\"ServerOverloaded\"}", HttpStatusCode.ServiceUnavailable)
            else respond("lyrics")
        }) { expectSuccess = true }
        try {
            suspend fun fetch(title: String) = guard.request(title, "artist", 180) {
                client.get("https://lrclib.test/api/search").bodyAsText()
            }
            assertNull(fetch("song"))
            assertNull(fetch("song"))
            assertNull(fetch("another"))
            assertEquals(1, requests)
            assertEquals(1, logs.size)
            testScheduler.advanceTimeBy(LrcLibRequestGuard.PROVIDER_COOLDOWN_MS)
            assertEquals("lyrics", fetch("another"))
            assertNull(fetch("song"))
            testScheduler.advanceTimeBy(LrcLibRequestGuard.TRANSIENT_TTL_MS - LrcLibRequestGuard.PROVIDER_COOLDOWN_MS)
            assertEquals("lyrics", fetch("song"))
            assertEquals(3, requests)
            assertEquals(1, logs.size)
        } finally { client.close() }
    }

    @Test fun concurrentQueriesCannotStormAnOverloadedProvider() = runTest {
        var requests = 0
        val logs = mutableListOf<String>()
        val guard = LrcLibRequestGuard({ testScheduler.currentTime }, logs::add)
        val client = HttpClient(MockEngine {
            requests++
            delay(100)
            respond("ServerOverloaded", HttpStatusCode.ServiceUnavailable)
        }) { expectSuccess = true }
        try {
            val results = (1..30).map { index ->
                async {
                    guard.request("song $index", "artist", 180) {
                        client.get("https://lrclib.test/api/search").bodyAsText()
                    }
                }
            }.awaitAll()
            assertTrue(results.all { it == null })
            assertEquals(1, requests)
            assertEquals(1, logs.size)
        } finally { client.close() }
    }

    @Test fun timeoutsOpenProviderCooldownAndAreNotCachedAsDefiniteMisses() = runTest {
        for (failure in listOf(SocketTimeoutException(), HttpRequestTimeoutException("https://lrclib.test", 100))) {
            val guard = LrcLibRequestGuard({ testScheduler.currentTime })
            assertNull(guard.request<String>("song", "artist", 180) { throw failure })
            assertNull(guard.request("other", "artist", 180) { fail("provider cooldown ignored"); "bad" })
            testScheduler.advanceTimeBy(LrcLibRequestGuard.TRANSIENT_TTL_MS)
            assertEquals("recovered", guard.request("song", "artist", 180) { "recovered" })
        }
    }

    @Test fun dnsFailureOnlyBlocksTheSpecificQuery() = runTest {
        val guard = LrcLibRequestGuard()
        assertNull(guard.request<String>("song", "artist", 180) { throw UnknownHostException() })
        assertNull(guard.request("song", "artist", 180) { fail("query cooldown ignored"); "bad" })
        assertEquals("other", guard.request("other", "artist", 180) { "other" })
    }

    @Test fun returnedCancellationIsRethrownAndNeverCached() = runTest {
        val logs = mutableListOf<String>()
        val guard = LrcLibRequestGuard(onTransientFailure = logs::add)
        val cancellation = CancellationException("track changed")
        try {
            guard.request<String>("song", "artist", 180) { Result.failure<String>(cancellation).getOrThrow() }
            fail("cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals("retry", guard.request("song", "artist", 180) { "retry" })
        assertTrue(logs.isEmpty())
    }

    @Test fun cancellingAnInFlightRequestReleasesGuardWithoutPoisoningNextCaller() = runTest {
        val guard = LrcLibRequestGuard()
        val preload = launch { guard.request("song", "artist", 180) { delay(10_000); "stale" } }
        runCurrent()
        preload.cancelAndJoin()
        assertEquals("foreground", guard.request("song", "artist", 180) { "foreground" })
    }

    @Test fun unexpectedExceptionsReachReporterAndNeverOpenCooldown() = runTest {
        val logs = mutableListOf<String>()
        val guard = LrcLibRequestGuard(onTransientFailure = logs::add)
        val unexpected = IllegalArgumentException("broken parser")
        var reported: Throwable? = null
        try {
            guard.request<String>("song", "artist", 180) { throw unexpected }
            fail("unexpected exception swallowed")
        } catch (failure: Exception) { reported = failure }
        assertSame(unexpected, reported)
        assertEquals("retry", guard.request("song", "artist", 180) { "retry" })
        assertTrue(logs.isEmpty())
    }
}
