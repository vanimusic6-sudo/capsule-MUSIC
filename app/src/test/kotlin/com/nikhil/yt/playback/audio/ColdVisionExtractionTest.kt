package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.playback.AudioWebStartupClientPolicy
import com.nikhil.yt.playback.audio.potoken.PoTokenMinter
import com.nikhil.yt.playback.audio.potoken.PoTokenSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ColdVisionExtractionTest {
    @Test fun pinnedExtractorReturnsVisionStreamWhileRealTokenSessionIsStillSuspended() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = PoTokenSession(create = {
            entered.complete(Unit)
            release.await()
            object : PoTokenMinter {
                override val isDead = false
                override val isExpired = false
                override suspend fun generatePoToken(identifier: String) = "fake-test-token"
                override fun close() {}
            }
        })
        val warmup = async { session.prewarm("visitor") }
        entered.await()
        var tokenRequests = 0
        var playerRequests = 0
        val provider = object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD))
            override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
                tokenRequests++
                return session.obtain(videoId, visitorData)?.let {
                    PoTokenResult(it.playerRequestPoToken, it.streamingDataPoToken, visitorData)
                }
            }
        }
        val http = HttpClient(MockEngine {
            playerRequests++
            respond(RESPONSE, headers = headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val innerTube = InnerTube(http).apply { visitorData = "visitor" }
        val configured = listOf(AudioClientOrder.WEB_REMIX, AudioClientOrder.VISIONOS, AudioClientOrder.VISIONOS_0_1)
        var requestedWarmup = 0
        try {
            val order = AudioWebStartupClientPolicy.prepareOrder(configured, session.isReady("visitor")) { requestedWarmup++ }
            assertEquals(AudioClientOrder.VISIONOS_0_1, order.first())
            val extractor = InnerTubeExtractor(
                configParser = object : YtConfigParser {
                    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                        PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "visitor", null)
                },
                cipherService = YouTubeCipherService(http), innerTube = innerTube,
                fallbackStrategy = CapsuleAudioClientStrategy, tokenProvider = provider,
            )
            val stream = withTimeout(2_000L) {
                extractor.extract("song", ContentHints(playbackClientOverrideId = order.first()))
            }
            assertEquals("VISIONOS_0_1__nopo", stream?.profileId)
            assertEquals(1, playerRequests)
            assertEquals(0, tokenRequests)
            assertEquals(1, requestedWarmup)
            assertFalse(warmup.isCompleted)
            release.complete(Unit)
            assertTrue(warmup.await())
            assertEquals(configured, AudioWebStartupClientPolicy.prepareOrder(configured, session.isReady("visitor")) { error("Already warm") })
        } finally {
            warmup.cancelAndJoin()
            session.close()
            http.close()
        }
    }

    private companion object {
        const val RESPONSE = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"song","title":"Track","author":"Artist","lengthSeconds":"60"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://r.googlevideo.com/videoplayback?expire=9999999999","mimeType":"audio/webm; codecs=\"opus\"","bitrate":128000}]}}"""
    }
}
