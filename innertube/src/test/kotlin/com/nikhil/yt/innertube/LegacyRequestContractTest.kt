package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class LegacyRequestContractTest {
    @Test fun ambiguousPlaylistWritesAreNotRetried() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; throw IOException("Response lost after server commit") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://music.youtube.com/youtubei/v1/") }
        }
        try {
            val api = InnerTube(client)
            assertTrue(runCatching { api.createPlaylist(YouTubeClient.WEB_REMIX, "new playlist") }.isFailure)
            assertEquals(1, requests)
            assertTrue(runCatching { api.addToPlaylist(YouTubeClient.WEB_REMIX, "playlist", "video") }.isFailure)
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun browseStillRetriesTransientIoFailures() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; throw IOException("Connection reset") }) {
            install(ContentNegotiation) { json() }
            defaultRequest { url("https://music.youtube.com/youtubei/v1/") }
        }
        try {
            assertTrue(runCatching { InnerTube(client).browse(YouTubeClient.WEB_REMIX, "browse") }.isFailure)
            assertEquals(3, requests)
        } finally { client.close() }
    }

    @Test fun cancellationEscapesResultWrappers() {
        val cancellation = CancellationException("obsolete request")
        val thrown = assertThrows(CancellationException::class.java) {
            runCatchingCancellable { throw cancellation }
        }
        assertSame(cancellation, thrown)
    }

    @Test fun concurrentAuthFieldUpdatesCannotOverwriteEachOther() {
        val client = HttpClient(MockEngine { error("No network expected") })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val api = InnerTube(client)
            val barrier = CyclicBarrier(2)
            fun update(cookie: Boolean) = pool.submit {
                val first = AtomicBoolean(true)
                api.updateAuthState { old ->
                    if (first.getAndSet(false)) barrier.await(5, TimeUnit.SECONDS)
                    if (cookie) old.copy(cookie = "SAPISID=test") else old.copy(visitorData = "visitor")
                }
            }
            val one = update(true)
            val two = update(false)
            one.get(5, TimeUnit.SECONDS)
            two.get(5, TimeUnit.SECONDS)
            assertEquals("SAPISID=test", api.authState.cookie)
            assertEquals("visitor", api.authState.visitorData)
        } finally { pool.shutdownNow(); client.close() }
    }

    @Test fun trackingCannotSendCredentialsToAnUntrustedHostOrRedirect() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf("Location", "https://untrusted.example/collect"))
        })
        try {
            val api = InnerTube(client).apply { authState = PlaybackAuthState(cookie = "SAPISID=test") }
            listOf("https://untrusted.example/api/stats/playback", "http://www.youtube.com/api/stats/playback",
                "https://www.youtube.com.evil.example/api/stats/playback", "https://www.youtube.com/watch").forEach {
                assertTrue(runCatching { api.registerPlayback(it, "nonce", null) }.isFailure)
            }
            assertEquals(0, requests)
            api.registerPlayback("https://www.youtube.com/api/stats/playback", "nonce", null)
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
