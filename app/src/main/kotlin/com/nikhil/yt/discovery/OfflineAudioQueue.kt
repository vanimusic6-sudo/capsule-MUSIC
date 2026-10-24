package com.nikhil.yt.discovery

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
        /**
         * A MediaStore deletion invalidates every queued reference to that URI.
         * Remove only the deleted recording, preserving all remaining YouTube
         * and external tracks in the user's play queue.
         */
        fun removeDeletedFromPlayer(player: Player, savedId: Long) {
            val target = PREFIX + savedId
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId == target) {
                    player.removeMediaItem(index)
                }
            }
        }
        fun metadata(file: ExperimentalDownload.SavedAudio) = MediaMetadata(
                id = PREFIX + file.id,
                title = file.title,
                duration = -1,
                thumbnailUrl = file.artwork,
                artists = listOf(MediaMetadata.Artist(id = null, name = file.artist)),
                sourceUrl = file.uri.toString(),
            )
        fun mediaItem(file: ExperimentalDownload.SavedAudio): MediaItem {
            val meta = metadata(file)
            return MediaItem.Builder()
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
        fun create(files: List<ExperimentalDownload.SavedAudio>,
                   selected: ExperimentalDownload.SavedAudio): OfflineAudioQueue? {
            val index = files.indexOfFirst { it.id == selected.id }
            if (index < 0) return null
            val media = files.map(::mediaItem)
            return OfflineAudioQueue(media, index, metadata(selected))
        }
    }
}
