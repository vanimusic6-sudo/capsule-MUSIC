package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.toSoundCloudMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Lazy SoundCloud queue.
 *
 * Only the selected track is resolved on the critical path. The rest of the
 * metadata list is resolved in tiny pages when MusicService asks for more,
 * instead of blocking a click on 10-100 sequential extractor requests.
 */
internal class SoundCloudQueue private constructor(
    private val queueTitle: String?,
    private val firstItem: MediaItem,
    private val pendingTracks: List<SoundCloudCatalog.Track>,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    private var nextTrackIndex = 0
    private var pageAnchor: MediaItem = firstItem

    override suspend fun getInitialStatus(): Queue.Status =
        Queue.Status(
            title = queueTitle,
            items = listOf(firstItem),
            mediaItemIndex = 0,
            position = 0L,
        )

    override fun hasNextPage(): Boolean = nextTrackIndex < pendingTracks.size

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!hasNextPage()) return@withContext listOf(pageAnchor)

        val end = (nextTrackIndex + PAGE_SIZE).coerceAtMost(pendingTracks.size)
        val batch = pendingTracks.subList(nextTrackIndex, end)
        nextTrackIndex = end

        val resolved = coroutineScope {
            batch.map { track ->
                async {
                    runCatching {
                        track.toSoundCloudMediaItem(
                            SoundCloudNewPipe.resolve(track.permalink)
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        if (resolved.isEmpty()) {
            // Queue.nextPage() has an overlap contract: MusicService drops item 0.
            return@withContext listOf(pageAnchor)
        }

        buildList(resolved.size + 1) {
            add(pageAnchor)
            addAll(resolved)
        }.also {
            pageAnchor = resolved.last()
        }
    }

    companion object {
        private const val PAGE_SIZE = 3

        suspend fun create(
            title: String?,
            tracks: List<SoundCloudCatalog.Track>,
            requestedStartUrl: String?,
        ): SoundCloudQueue? = withContext(Dispatchers.IO) {
            val unique = tracks.distinctBy { it.permalink }
            if (unique.isEmpty()) return@withContext null

            val startIndex = requestedStartUrl
                ?.let { url -> unique.indexOfFirst { it.permalink == url } }
                ?.takeIf { it >= 0 }
                ?: 0
            val startTrack = unique[startIndex]

            // One blocking resolve, not the entire search/playlist.
            val firstItem = runCatching {
                startTrack.toSoundCloudMediaItem(
                    SoundCloudNewPipe.resolve(startTrack.permalink)
                )
            }.getOrNull() ?: return@withContext null

            val pending = buildList {
                addAll(unique.drop(startIndex + 1))
                addAll(unique.take(startIndex))
            }

            SoundCloudQueue(
                queueTitle = title,
                firstItem = firstItem,
                pendingTracks = pending,
            )
        }
    }
}
