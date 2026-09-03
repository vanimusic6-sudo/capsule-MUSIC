package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import io.ktor.client.call.body
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Temporary CI probe; removed after the live response has been inspected. */
class LivePlayerClientDiagnosticTest {
    @Test
    fun reportCurrentAnonymousPlayerResponses() =
        runBlocking {
            assumeTrue(System.getenv("CAPSULE_LIVE_CLIENT_TEST") == "1")

            val innerTube = InnerTube()
            val videoId = "mzhbRUniU4U"
            val clients =
                listOf(
                    YouTubeClient.VISIONOS,
                    YouTubeClient.WEB_EMBEDDED,
                    YouTubeClient.TVHTML5_DOWNGRADED,
                    YouTubeClient.TVHTML5,
                )
            val lines = mutableListOf<String>()
            for (client in clients) {
                lines +=
                    runCatching {
                        innerTube
                            .player(
                                client = client,
                                videoId = videoId,
                                playlistId = null,
                                signatureTimestamp = null,
                            ).body<PlayerResponse>()
                    }.fold(
                        onSuccess = { response ->
                            val audio =
                                response.streamingData
                                    ?.adaptiveFormats
                                    .orEmpty()
                                    .count { it.isAudio }
                            val loudness =
                                response.playerConfig
                                    ?.audioConfig
                                    ?.loudnessDb
                                    ?: response.streamingData
                                        ?.adaptiveFormats
                                        .orEmpty()
                                        .firstOrNull { it.isAudio }
                                        ?.loudnessDb
                            "${client.friendlyName}: " +
                                "${response.playabilityStatus.status}; " +
                                "reason=${response.playabilityStatus.reason}; " +
                                "audio=$audio; loudness=$loudness"
                        },
                        onFailure = { throwable ->
                            "${client.friendlyName}: " +
                                "${throwable.javaClass.simpleName}; " +
                                "${throwable.message}"
                        },
                    )
            }
            val result = lines.joinToString(separator = "\n")

            fail("CAPSULE LIVE CLIENT REPORT\n$result")
        }
}
