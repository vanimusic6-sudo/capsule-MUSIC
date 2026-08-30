/*
 * Capsule MUSIC
 * YouTube Music SONG <-> VIDEO link resolver.
 *
 * Uses the same "next" response field that YouTube Music exposes for
 * switching the current song to its linked official music video.
 * No title search is performed.
 *
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.NextResponse
import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class YouTubeMusicVideoLink(
    val videoId: String,
    val musicVideoType: String,
)

object YouTubeMusicVideoLinkResolver {
    private val mutex = Mutex()
    private val innerTube = InnerTube()

    suspend fun resolve(audioVideoId: String): Result<YouTubeMusicVideoLink> = runCatching {
        val canonicalId = audioVideoId.trim()
        require(canonicalId.isNotBlank()) { "Missing YouTube Music track id" }

        mutex.withLock {
            syncSession()

            val response =
                innerTube.next(
                    client = WEB_REMIX,
                    videoId = canonicalId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = null,
                ).body<NextResponse>()

            val endpoint =
                response.currentVideoEndpoint?.anyWatchEndpoint
                    ?: throw IllegalStateException(
                        "YouTube Music did not provide a linked video for this track",
                    )

            val linkedVideoId =
                endpoint.videoId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "YouTube Music returned an empty linked video id",
                    )

            val musicVideoType =
                endpoint.watchEndpointMusicSupportedConfigs
                    ?.watchEndpointMusicConfig
                    ?.musicVideoType
                    ?: throw IllegalStateException(
                        "YouTube Music did not identify the linked item as an official music video",
                    )

            /*
             * Be deliberately strict. MUSIC_VIDEO_TYPE_UGC can contain
             * user-generated uploads, which is exactly what Capsule should
             * avoid when the user asks for the YouTube Music-style switch.
             */
            if (musicVideoType != MUSIC_VIDEO_TYPE_OMV) {
                throw IllegalStateException(
                    "No official YouTube Music video is linked to this track",
                )
            }

            YouTubeMusicVideoLink(
                videoId = linkedVideoId,
                musicVideoType = musicVideoType,
            )
        }
    }

    private fun syncSession() {
        innerTube.locale = YouTube.locale
        innerTube.authState = YouTube.authState
        innerTube.useLoginForBrowse = YouTube.useLoginForBrowse

        val proxy = YouTube.proxy
        if (innerTube.proxy != proxy) {
            innerTube.proxy = proxy
        }
    }
}
