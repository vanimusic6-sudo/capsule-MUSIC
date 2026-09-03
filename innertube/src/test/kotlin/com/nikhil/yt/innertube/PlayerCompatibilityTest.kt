package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeLocale
import com.nikhil.yt.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun everyPlayerPayloadCarriesHtml5Preference() {
        listOf(
            YouTubeClient.VISIONOS,
            YouTubeClient.WEB_EMBEDDED,
            YouTubeClient.TVHTML5,
        ).forEach { client ->
            val payload =
                buildPlayerRequestPayload(
                    client = client,
                    locale = YouTubeLocale(gl = "US", hl = "en"),
                    visitorData = "visitor",
                    dataSyncId = null,
                    videoId = "video",
                    playlistId = null,
                    signatureTimestamp = null,
                    poToken = null,
                )
            val playback =
                payload["playbackContext"]!!
                    .jsonObject["contentPlaybackContext"]!!
                    .jsonObject

            assertEquals(
                "HTML5_PREF_WANTS",
                playback["html5Preference"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun embeddedBootstrapFlagsReachPlayerPayload() {
        val bootstrap =
            parsePlayerBootstrapConfig(
                html =
                    """
                    <script>
                    ytcfg.set({
                      "INNERTUBE_API_KEY":"public-key",
                      "STS":20420,
                      "INNERTUBE_CONTEXT":{"client":{"clientName":"WEB_EMBEDDED_PLAYER"}}
                    });
                    ytcfg.set({
                      "WEB_PLAYER_CONTEXT_CONFIGS":{
                        "WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER":{
                          "encryptedHostFlags":"encrypted-flags"
                        }
                      }
                    });
                    </script>
                    """.trimIndent(),
                client = YouTubeClient.WEB_EMBEDDED,
            )

        assertEquals("public-key", bootstrap.apiKey)
        assertEquals(20420, bootstrap.signatureTimestamp)
        assertEquals("encrypted-flags", bootstrap.encryptedHostFlags)

        val payload =
            buildPlayerRequestPayload(
                client = YouTubeClient.WEB_EMBEDDED,
                locale = YouTubeLocale(gl = "US", hl = "en"),
                visitorData = "visitor",
                dataSyncId = null,
                videoId = "video",
                playlistId = null,
                signatureTimestamp = null,
                poToken = null,
                bootstrap = bootstrap,
            )
        val playback =
            payload["playbackContext"]!!
                .jsonObject["contentPlaybackContext"]!!
                .jsonObject

        assertEquals(20420, playback["signatureTimestamp"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "encrypted-flags",
            playback["encryptedHostFlags"]!!.jsonPrimitive.content,
        )
        assertEquals(
            YouTubeClient.THIRD_PARTY_EMBED_URL,
            payload["context"]!!
                .jsonObject["thirdParty"]!!
                .jsonObject["embedUrl"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun tvBootstrapKeepsRuntimeConfigButDropsInstallBlob() {
        val bootstrap =
            parsePlayerBootstrapConfig(
                html =
                    """
                    <script>ytcfg.set({
                      "INNERTUBE_CONTEXT":{
                        "client":{
                          "clientName":"WRONG",
                          "clientVersion":"0",
                          "visitorData":"page-visitor",
                          "configInfo":{
                            "appInstallData":"large-install-blob",
                            "hotHashData":"hot-config"
                          }
                        }
                      }
                    });</script>
                    """.trimIndent(),
                client = YouTubeClient.TVHTML5,
            )
        val payload =
            buildPlayerRequestPayload(
                client = YouTubeClient.TVHTML5,
                locale = YouTubeLocale(gl = "DE", hl = "de"),
                visitorData = "session-visitor",
                dataSyncId = null,
                videoId = "video",
                playlistId = null,
                signatureTimestamp = 20421,
                poToken = null,
                bootstrap = bootstrap,
            )
        val client =
            payload["context"]!!
                .jsonObject["client"]!!
                .jsonObject
        val configInfo = client["configInfo"]!!.jsonObject

        assertEquals("TVHTML5", client["clientName"]!!.jsonPrimitive.content)
        assertEquals(
            YouTubeClient.TVHTML5.clientVersion,
            client["clientVersion"]!!.jsonPrimitive.content,
        )
        assertEquals("session-visitor", client["visitorData"]!!.jsonPrimitive.content)
        assertFalse("appInstallData" in configInfo)
        assertEquals("hot-config", configInfo["hotHashData"]!!.jsonPrimitive.content)
    }
}
