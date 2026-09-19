package com.nikhil.yt.innertube.video

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoExtractionRequestsTest {
    @Test fun cancellationArrivingBeforeResolvePreventsAnyWork() {
        val id = UUID.randomUUID().toString()
        var calls = 0
        VideoExtractionRequests.cancel(id)
        val result = runCatching { VideoExtractionRequests.withRequest(id, 5_000L) { calls++ } }
        assertTrue(result.isFailure)
        assertEquals(0, calls)
        assertEquals("ok", VideoExtractionRequests.withRequest(UUID.randomUUID().toString(), 5_000L) { "ok" })
    }

    @Test fun cancellingExtractionAbortsTheActualHttpRead() {
        assertHttpReadStops(cancelExplicitly = true)
    }

    @Test fun oneDeadlineBoundsTheEntireExtractionAndAbortsItsHttpRead() {
        assertHttpReadStops(cancelExplicitly = false)
    }

    private fun assertHttpReadStops(cancelExplicitly: Boolean) {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        val id = UUID.randomUUID().toString()
        try {
            executor.submit {
                server.accept().use {
                    accepted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            val result = executor.submit<Result<Unit>> {
                runCatching {
                    VideoExtractionRequests.withRequest(id, if (cancelExplicitly) 5_000L else 500L) {
                        VideoExtractionRequests.execute(call) {
                            call.execute().use { it.body.string() }
                        }
                    }
                    Unit
                }
            }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            if (cancelExplicitly) VideoExtractionRequests.cancel(id)
            assertTrue(result.get(3, TimeUnit.SECONDS).isFailure)
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            call.cancel()
            server.close()
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
