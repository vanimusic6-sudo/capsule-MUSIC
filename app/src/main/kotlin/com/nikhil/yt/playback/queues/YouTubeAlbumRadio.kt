/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeAlbumRadio(
    private var playlistId: String,
    private var albumSongCount: Int = 0,
    private var continuation: String? = null,
    private var firstTimeLoaded: Boolean = false,
    private var restoredStatus: Queue.Status? = null,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    internal data class PersistenceState(
        val playlistId: String,
        val albumSongCount: Int,
        val continuation: String?,
        val firstTimeLoaded: Boolean,
    )

    internal fun persistenceState(): PersistenceState =
        PersistenceState(
            playlistId = playlistId,
            albumSongCount = albumSongCount,
            continuation = continuation,
            firstTimeLoaded = firstTimeLoaded,
        )

    private val endpoint: WatchEndpoint
        get() =
            WatchEndpoint(
                playlistId = playlistId,
                params = "wAEB",
            )

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(IO) {
            restoredStatus?.let { status ->
                restoredStatus = null
                return@withContext status
            }

            val albumSongs = YouTube.albumSongs(playlistId).getOrThrow()
            albumSongCount = albumSongs.size
            Queue.Status(
                title = albumSongs.firstOrNull()?.album?.name.orEmpty(),
                items = albumSongs.map { it.toMediaItem() },
                mediaItemIndex = 0,
            )
        }

    override fun hasNextPage(): Boolean = !firstTimeLoaded || continuation != null

    override suspend fun nextPage(): List<MediaItem> =
        withContext(IO) {
            val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
            continuation = nextResult.continuation
            if (!firstTimeLoaded) {
                firstTimeLoaded = true
                nextResult.items
                    .drop(albumSongCount.coerceAtLeast(0))
                    .map { it.toMediaItem() }
            } else {
                nextResult.items.map { it.toMediaItem() }
            }
        }
}
