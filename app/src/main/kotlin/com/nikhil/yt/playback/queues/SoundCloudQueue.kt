package com.nikhil.yt.playback.queues

import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.toSoundCloudMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Source-specific queue for SoundCloud.
 *
 * The queue preserves SoundCloud ordering and resolves only SoundCloud URLs through
 * NewPipeExtractor. Failed/unavailable tracks are skipped instead of ever falling
 * back to YouTube. Signed CDN URLs remain isolated from Capsule's YouTube resolver.
 */
internal class SoundCloudQueue private constructor(
    private val queueTitle: String?,
    private val items: List<androidx.media3.common.MediaItem>,
    private val startIndex: Int,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status(
            title = queueTitle,
            items = items,
            mediaItemIndex = startIndex,
            position = 0L,
        )

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage(): List<androidx.media3.common.MediaItem> =
        throw UnsupportedOperationException("SoundCloudQueue is fully materialized")

    companion object {
        suspend fun create(
            title: String?,
            tracks: List<SoundCloudCatalog.Track>,
            requestedStartUrl: String?,
        ): SoundCloudQueue? = withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext null

            val mediaItems = ArrayList<androidx.media3.common.MediaItem>(tracks.size)
            var requestedStartIndex: Int? = null

            tracks.distinctBy { it.permalink }.forEach { track ->
                val mediaItem = runCatching {
                    val stream = SoundCloudNewPipe.resolve(track.permalink)
                    track.toSoundCloudMediaItem(stream)
                }.getOrNull() ?: return@forEach

                if (track.permalink == requestedStartUrl) {
                    requestedStartIndex = mediaItems.size
                }
                mediaItems += mediaItem
            }

            if (mediaItems.isEmpty()) return@withContext null
            SoundCloudQueue(
                queueTitle = title,
                items = mediaItems,
                startIndex = (requestedStartIndex ?: 0).coerceIn(0, mediaItems.lastIndex),
            )
        }
    }
}
