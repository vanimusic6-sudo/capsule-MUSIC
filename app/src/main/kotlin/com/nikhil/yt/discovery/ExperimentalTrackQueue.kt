package com.nikhil.yt.discovery

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.queues.Queue

fun ExperimentalCatalog.Track.toCapsuleMetadata() = MediaMetadata(
    id = mediaId,
    title = title,
    artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
    duration = durationSeconds,
    thumbnailUrl = artwork,
    sourceUrl = sourcePage,
)

/** Only explicitly playable HTTPS tracks are handed to Media3. Mirlo requires consent/token first. */
class ExperimentalTrackQueue private constructor(
    private val title: String,
    private val items: List<MediaItem>,
    private val startIndex: Int,
    override val preloadItem: MediaMetadata,
) : Queue {
    override suspend fun getInitialStatus() =
        Queue.Status(title, items, startIndex, 0L)

    override fun hasNextPage() = false
    override suspend fun nextPage(): List<MediaItem> = emptyList()

    companion object {
        fun create(
            title: String,
            tracks: List<ExperimentalCatalog.Track>,
            start: ExperimentalCatalog.Track,
        ): ExperimentalTrackQueue? {
            val playable = tracks.filter { it.playable }.distinctBy { it.mediaId }
            val index = playable.indexOfFirst { it.mediaId == start.mediaId }
            if (index == -1) return null
            return ExperimentalTrackQueue(
                title = title,
                items = playable.map { track ->
                    val metadata = track.toCapsuleMetadata()
                    MediaItem.Builder()
                        .setMediaId(track.mediaId)
                        .setUri(requireNotNull(track.streamUrl))
                        .setTag(metadata)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setArtworkUri(track.artwork?.toUri())
                                .setMediaType(MEDIA_TYPE_MUSIC)
                                .build(),
                        )
                        .build()
                },
                startIndex = index,
                preloadItem = start.toCapsuleMetadata(),
            )
        }
    }
}
