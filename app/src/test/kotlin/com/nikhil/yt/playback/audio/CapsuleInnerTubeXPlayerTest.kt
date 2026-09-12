package com.nikhil.yt.playback.audio

import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleInnerTubeXPlayerTest {
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
