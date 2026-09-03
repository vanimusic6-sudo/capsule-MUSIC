package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCompatibilityTest {
    @Test
    fun nativeAndWebClientsUseMainYouTubeHost() {
        listOf(
            YouTubeClient.VISIONOS,
            YouTubeClient.IOS,
            YouTubeClient.WEB,
            YouTubeClient.MWEB,
            YouTubeClient.WEB_EMBEDDED,
            YouTubeClient.TVHTML5,
        ).forEach { client ->
            val profile = resolvePlayerRequestProfile(client)
            assertEquals(YouTubeClient.ORIGIN_YOUTUBE, profile.origin)
            assertTrue(profile.endpoint.startsWith(YouTubeClient.ORIGIN_YOUTUBE))
        }
    }

    @Test
    fun musicClientsKeepMusicHost() {
        listOf(
            YouTubeClient.WEB_REMIX,
            YouTubeClient.IOS_MUSIC,
            YouTubeClient.ANDROID_MUSIC,
        ).forEach { client ->
            val profile = resolvePlayerRequestProfile(client)
            assertEquals(YouTubeClient.ORIGIN_YOUTUBE_MUSIC, profile.origin)
        }
    }

    @Test
    fun embeddedClientUsesRealThirdPartyContext() {
        val profile = resolvePlayerRequestProfile(YouTubeClient.WEB_EMBEDDED)
        assertEquals(YouTubeClient.THIRD_PARTY_EMBED_URL, profile.referer)
        assertFalse(profile.referer.contains("youtube.com"))
    }

    @Test
    fun webTokenIsOptInAndNeverAttachedToEmbeddedOrTv() {
        val enabled =
            PlaybackAuthState(
                poToken = "real-token",
                webClientPoTokenEnabled = true,
            )
        val disabled = enabled.copy(webClientPoTokenEnabled = false)

        assertEquals("real-token", enabled.resolvePlayerPoToken(YouTubeClient.WEB))
        assertNull(disabled.resolvePlayerPoToken(YouTubeClient.WEB))
        assertNull(enabled.resolvePlayerPoToken(YouTubeClient.WEB_EMBEDDED))
        assertNull(enabled.resolvePlayerPoToken(YouTubeClient.TVHTML5))
    }

    @Test
    fun slimPlayerResponseDoesNotLosePlayableAudio() {
        val response =
            Json.decodeFromString<PlayerResponse>(
                """
                {
                  "playabilityStatus": { "status": "OK" },
                  "streamingData": {
                    "adaptiveFormats": [{
                      "itag": 251,
                      "mimeType": "audio/webm; codecs=\"opus\"",
                      "bitrate": 128000,
                      "quality": "tiny"
                    }]
                  }
                }
                """.trimIndent(),
            )

        assertEquals("OK", response.playabilityStatus.status)
        assertEquals(251, response.streamingData?.adaptiveFormats?.single()?.itag)
        assertNull(response.playerConfig)
        assertNull(response.videoDetails)
    }
}
