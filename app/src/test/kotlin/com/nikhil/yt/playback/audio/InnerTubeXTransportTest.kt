package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise the pinned extractor, its internal retries, Ktor, OkHttp, and actual HTTP responses. */
class InnerTubeXTransportTest {
    @Before fun before() = CapsulePlaybackSafety.clear()
    @After fun after() = CapsulePlaybackSafety.clear()

    @Test fun rawHttp200BotCheckIsPreservedAndFurtherRequestsNeverReachTheServer() = runBlocking {
        withFixture(200, """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""") { extractor, requests ->
            assertTrue(runCatching { extractor.extract("one", hints()) }.isFailure)
            assertNotNull(CapsulePlaybackSafety.blockedExceptionOrNull())
            assertTrue(runCatching { extractor.extract("two", hints()) }.isFailure)
            assertEquals(1, requests.get())
        }
    }

    @Test fun raw429StopsEvenTheLibrarysNestedRetries() = runBlocking {
        withFixture(429, "Too Many Requests") { extractor, requests ->
            assertTrue(runCatching { extractor.extract("one", hints()) }.isFailure)
            assertNotNull(CapsulePlaybackSafety.blockedExceptionOrNull())
            assertEquals(1, requests.get())
        }
    }

    @Test fun ageRestrictionDoesNotBlockOtherTracks() = runBlocking {
        withFixture(200, """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}""") { extractor, requests ->
            runCatching { extractor.extract("one", hints()) }
            runCatching { extractor.extract("two", hints()) }
            assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
            assertEquals(2, requests.get())
        }
    }

    @Test fun inspectingSuccessfulResponsesDoesNotConsumeTheAudioContract() = runBlocking {
        val body = """{
            "playabilityStatus":{"status":"OK"},
            "videoDetails":{"videoId":"one","title":"I'm not a bot","lengthSeconds":"60"},
            "streamingData":{"expiresInSeconds":"3600","adaptiveFormats":[{
                "itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,
                "contentLength":"1000","audioQuality":"AUDIO_QUALITY_MEDIUM","quality":"tiny",
                "url":"https://rr1.googlevideo.com/videoplayback?expire=9999999999"}]}
        }"""
        withFixture(200, body) { extractor, requests ->
            val stream = requireNotNull(extractor.extract("one", hints()))
            assertEquals(140, stream.itag)
            assertEquals(1000L, stream.contentLengthBytes)
            assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
            assertEquals(1, requests.get())
        }
    }

    private fun hints() = ContentHints(playbackClientOverrideId = "VISIONOS")
        .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = false)

    private suspend fun withFixture(
        status: Int,
        body: String,
        block: suspend (InnerTubeExtractor, AtomicInteger) -> Unit,
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                requests.incrementAndGet()
                it.requestBody.readBytes()
                val bytes = body.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        val client = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            engine {
                config {
                    retryOnConnectionFailure(false)
                    addInterceptor(CapsuleAudioRequestInterceptor())
                    // Redirect only the test transport, after the production YouTube guard.
                    addInterceptor { chain ->
                        val local = chain.request().url.newBuilder().scheme("http")
                            .host("127.0.0.1").port(server.address.port).build()
                        chain.proceed(chain.request().newBuilder().url(local).build())
                    }
                }
            }
        }
        val innerTube = InnerTube(client, retryDelay = {})
        val cipher = YouTubeCipherService(client)
        try {
            val parser = object : YtConfigParser {
                override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                    PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, null, null)
            }
            block(InnerTubeExtractor(parser, cipher, innerTube, CapsuleAudioClientStrategy), requests)
        } finally {
            cipher.dispose()
            innerTube.close()
            client.close()
            server.stop(0)
        }
    }
}
