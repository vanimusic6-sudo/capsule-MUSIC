package com.nikhil.yt.discovery

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as ExoMetadata
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.queues.Queue

/**
 * MediaStore content:// recordings play inside Capsule without an HTTP request,
 * and never enter the YouTube audio resolver or DownloadIndex.
 */
class OfflineAudioQueue private constructor(
    private val entries: List<MediaItem>,
    private val position: Int,
    override val preloadItem: MediaMetadata,
) : Queue {
    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status("Downloaded", entries, position, 0L)

    override fun hasNextPage(): Boolean = false
    override suspend fun nextPage(): List<MediaItem> = emptyList()

    companion object {
        const val PREFIX = ExperimentalCatalog.EXPERIMENTAL_MEDIA_PREFIX + "offline:"
        fun create(files: List<ExperimentalDownload.SavedAudio>,
                   selected: ExperimentalDownload.SavedAudio): OfflineAudioQueue? {
            val index = files.indexOfFirst { it.id == selected.id }
            if (index < 0) return null
            fun metadata(file: ExperimentalDownload.SavedAudio) = MediaMetadata(
                id = PREFIX + file.id,
                title = file.title,
                duration = -1,
                thumbnailUrl = file.artwork,
                artists = listOf(MediaMetadata.Artist(id = null, name = file.artist)),
                sourceUrl = file.uri.toString(),
            )
            val media = files.map { file ->
                val meta = metadata(file)
                MediaItem.Builder()
                    .setMediaId(meta.id)
                    .setUri(file.uri)
                    .setTag(meta)
                    .setMediaMetadata(ExoMetadata.Builder()
                        .setTitle(file.title)
                        .setArtist(file.artist)
                        .setArtworkUri(file.artwork?.let(android.net.Uri::parse))
                        .setMediaType(ExoMetadata.MEDIA_TYPE_MUSIC)
                        .build())
                    .build()
            }
            return OfflineAudioQueue(media, index, metadata(selected))
        }
    }
}
