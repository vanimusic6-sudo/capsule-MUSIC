/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.extensions

import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.models.QueueData
import com.nikhil.yt.models.QueueType
import com.nikhil.yt.playback.queues.ListQueue
import com.nikhil.yt.playback.queues.LocalAlbumRadio
import com.nikhil.yt.playback.queues.Queue
import com.nikhil.yt.playback.queues.YouTubeAlbumRadio
import com.nikhil.yt.playback.queues.YouTubeQueue

fun Queue.toPersistQueue(
    title: String?,
    items: List<MediaMetadata>,
    mediaItemIndex: Int,
    position: Long,
): PersistQueue =
    when (this) {
        is ListQueue ->
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LIST,
            )

        is YouTubeQueue -> {
            val state = persistenceState()
            val endpoint = state.endpoint
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.YOUTUBE,
                queueData =
                    QueueData.YouTubeData(
                        endpoint = QueueData.YouTubeData.STRUCTURED_ENDPOINT,
                        continuation = state.continuation,
                        videoId = endpoint.videoId,
                        playlistId = endpoint.playlistId,
                        playlistSetVideoId = endpoint.playlistSetVideoId,
                        params = endpoint.params,
                        index = endpoint.index,
                        musicVideoType =
                            endpoint.watchEndpointMusicSupportedConfigs
                                ?.watchEndpointMusicConfig
                                ?.musicVideoType,
                    ),
            )
        }

        is YouTubeAlbumRadio -> {
            val state = persistenceState()
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.YOUTUBE_ALBUM_RADIO,
                queueData =
                    QueueData.YouTubeAlbumRadioData(
                        playlistId = state.playlistId,
                        albumSongCount = state.albumSongCount,
                        continuation = state.continuation,
                        firstTimeLoaded = state.firstTimeLoaded,
                    ),
            )
        }

        is LocalAlbumRadio -> {
            val state = persistenceState()
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LOCAL_ALBUM_RADIO,
                queueData =
                    QueueData.LocalAlbumRadioData(
                        albumId = state.albumId,
                        startIndex = state.startIndex,
                        playlistId = state.playlistId,
                        continuation = state.continuation,
                        firstTimeLoaded = state.firstTimeLoaded,
                        initialSongCount = state.initialSongCount,
                    ),
            )
        }

        else ->
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LIST,
            )
    }

fun PersistQueue.toQueue(): Queue {
    val restoredStatus =
        Queue.Status(
            title = title,
            items = items.map { it.toMediaItem() },
            mediaItemIndex = mediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            position = position.coerceAtLeast(0L),
        )

    fun listFallback(): Queue =
        ListQueue(
            title = title,
            items = restoredStatus.items,
            startIndex = restoredStatus.mediaItemIndex,
            position = restoredStatus.position,
        )

    return when (queueType) {
        is QueueType.LIST -> listFallback()

        is QueueType.YOUTUBE -> {
            val data = queueData as? QueueData.YouTubeData
            if (
                data == null ||
                data.endpoint != QueueData.YouTubeData.STRUCTURED_ENDPOINT
            ) {
                /* Old snapshots only contained the literal "youtube_queue". */
                return listFallback()
            }

            val musicConfig =
                data.musicVideoType
                    ?.takeIf { it.isNotBlank() }
                    ?.let { musicVideoType ->
                        WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                            WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(
                                musicVideoType = musicVideoType,
                            ),
                        )
                    }

            YouTubeQueue(
                endpoint =
                    WatchEndpoint(
                        videoId = data.videoId,
                        playlistId = data.playlistId,
                        playlistSetVideoId = data.playlistSetVideoId,
                        params = data.params,
                        index = data.index,
                        watchEndpointMusicSupportedConfigs = musicConfig,
                    ),
                continuation = data.continuation,
                restoredStatus = restoredStatus,
            )
        }

        is QueueType.YOUTUBE_ALBUM_RADIO -> {
            val data = queueData as? QueueData.YouTubeAlbumRadioData
            if (
                data == null ||
                data.playlistId.isBlank() ||
                data.playlistId == "youtube_album_radio"
            ) {
                return listFallback()
            }

            YouTubeAlbumRadio(
                playlistId = data.playlistId,
                albumSongCount = data.albumSongCount.coerceAtLeast(0),
                continuation = data.continuation,
                firstTimeLoaded = data.firstTimeLoaded,
                restoredStatus = restoredStatus,
            )
        }

        is QueueType.LOCAL_ALBUM_RADIO -> {
            val data = queueData as? QueueData.LocalAlbumRadioData
            if (
                data == null ||
                data.albumId.isBlank() ||
                data.albumId == "local_album_radio"
            ) {
                return listFallback()
            }

            LocalAlbumRadio.restore(
                albumId = data.albumId,
                playlistId = data.playlistId,
                continuation = data.continuation,
                firstTimeLoaded = data.firstTimeLoaded,
                initialSongCount =
                    data.initialSongCount
                        .takeIf { it > 0 }
                        ?: restoredStatus.items.size,
                status = restoredStatus,
            )
        }
    }
}
