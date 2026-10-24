package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class, manifest = org.robolectric.annotation.Config.NONE)
class CapsuleInnerTubeXPlayerTest {
    @Test fun aacChoiceUsesStrictMp4SelectionAndKeepsItAcrossPlaybackNormalization() {
        val network = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.net.ConnectivityManager::class.java)
        val mapped = CapsuleInnerTubeXPlayer.run { com.nikhil.yt.constants.AudioQuality.AAC.toInnerTubeX(network) }
        assertEquals(com.metrolist.innertubex.extraction.AudioQuality.MP4, mapped)
    }

    @Test fun exactVisionProfileSurvivesExtractionWithoutChangingWireHeaders() {
        val headers = mapOf("User-Agent" to "Vision 0.1 user agent")
        val extracted = com.metrolist.innertubex.extraction.ExtractedStream(
            videoId = "song", audioUrl = "https://edge.googlevideo.com/audio", headers = headers,
            loudnessDb = null, expiresAt = null, contentLengthBytes = 100L, itag = 140,
            mimeType = "audio/mp4", codecs = "mp4a.40.2", bitrate = 128000, sampleRate = 44100,
            clientName = "VISIONOS", profileId = "VISIONOS_0_1__nopo", requireBoundedRange = false,
            rangeChunkSizeBytes = 1_048_576L,
        )
        val playback = CapsuleInnerTubeXPlayer.run { extracted.toPlaybackData() }
        assertEquals("VISIONOS_0_1", playback.streamClient)
        assertEquals(headers, playback.streamHeaders)
    }

    @Test
    fun failedWatchPageFallsBackToAnonymousEmbeddedConfig() =
        runBlocking {
            val expected = PlayerConfig("embedded", 123, null, null)
            var embeddedUsesLogin = true
            val parser =
                object : YtConfigParser {
                    override suspend fun fetchConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig = error("HTTP 302")

                    override suspend fun fetchEmbeddedConfig(
                        videoId: String,
                        useLoginCookies: Boolean,
                    ): PlayerConfig {
                        embeddedUsesLogin = useLoginCookies
                        return expected
                    }
                }

            val recovered =
                CapsuleInnerTubeXPlayer.run {
                    parser.withEmbeddedConfigFallback()
                }.fetchConfig("song", useLoginCookies = true)

            assertEquals(expected, recovered)
            assertEquals(false, embeddedUsesLogin)
        }
}
