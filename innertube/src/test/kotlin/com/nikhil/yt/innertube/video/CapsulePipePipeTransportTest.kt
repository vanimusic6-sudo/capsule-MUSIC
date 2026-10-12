package com.nikhil.yt.innertube.video

import dev.maxrave.pipepipe.extractor.downloader.Downloader
import dev.maxrave.pipepipe.extractor.downloader.Request
import dev.maxrave.pipepipe.extractor.downloader.Response
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CapsulePipePipeTransportTest {
    @Test fun anonymousHeadersStripIdentityButKeepUpstreamClientAndServiceCookies() {
        val safe = anonymousVideoHeaders(mapOf(
            "Authorization" to listOf("account"), "X-Goog-AuthUser" to listOf("0"),
            "Accept-Encoding" to listOf("gzip"), "X-YouTube-Client-Name" to listOf("57"),
            "Cookie" to listOf("SID=account; __Secure-3PSID=account; SOCS=anonymous; PREF=hl=en")))
        assertFalse(safe.keys.any { it.equals("Authorization", true) || it.equals("Accept-Encoding", true) })
        assertEquals(listOf("57"), safe["X-YouTube-Client-Name"])
        assertEquals(listOf("SOCS=anonymous; PREF=hl=en"), safe["Cookie"])
    }
    @Test fun asynchronousPipeRequestIsCancelledAtTheRouteDeadlineAndFallbackStaysAlive() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient()
        val downloader = CapsulePipePipeDownloader(client)
        try {
            executor.submit { server.accept().use { accepted.countDown(); release.await(5, TimeUnit.SECONDS) } }
            val result = VideoExtractionRequests.withRequest(UUID.randomUUID().toString(), 5_000L) {
                try {
                    VideoExtractionRequests.withRouteBudget(500L) {
                        val call = downloader.executeAsync(Request.newBuilder().get("http://127.0.0.1:${server.localPort}/").build(),
                            object : Downloader.AsyncCallback {
                                override fun onSuccess(response: Response) { completed.countDown() }
                                override fun onError(e: Exception) { completed.countDown() }
                            })
                        assertTrue(accepted.await(2, TimeUnit.SECONDS))
                        assertTrue(completed.await(2, TimeUnit.SECONDS))
                        assertTrue(call.isCancelled)
                    }
                    error("The expired route cannot return a result")
                } catch (expected: SocketTimeoutException) {
                    VideoExtractionRequests.withRouteBudget(1_000L) { "next route" }
                }
            }
            assertEquals("next route", result)
        } finally {
            release.countDown(); server.close(); executor.shutdownNow()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
