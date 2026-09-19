/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.db.entities.AlbumWithSongs
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class LocalAlbumRadio private constructor(
    private val albumWithSongs: AlbumWithSongs?,
    private val startIndex: Int,
    private val restoredAlbumId: String?,
    private var playlistId: String?,
    private var continuation: String?,
    private var firstTimeLoaded: Boolean,
    private var restoredStatus: Queue.Status?,
    private var initialSongCount: Int,
) : Queue {
    constructor(
        albumWithSongs: AlbumWithSongs,
        startIndex: Int = 0,
    ) : this(
        albumWithSongs = albumWithSongs,
        startIndex = startIndex,
        restoredAlbumId = null,
        playlistId = null,
        continuation = null,
        firstTimeLoaded = false,
        restoredStatus = null,
        initialSongCount = albumWithSongs.songs.size,
    )

    override val preloadItem: MediaMetadata? = null

    internal data class PersistenceState(
        val albumId: String,
        val startIndex: Int,
        val playlistId: String?,
        val continuation: String?,
        val firstTimeLoaded: Boolean,
        val initialSongCount: Int,
    )

    internal fun persistenceState(): PersistenceState =
        PersistenceState(
            albumId = albumId,
            startIndex = startIndex,
            playlistId = playlistId,
            continuation = continuation,
            firstTimeLoaded = firstTimeLoaded,
            initialSongCount = initialSongCount,
        )

    private val albumId: String
        get() =
            albumWithSongs?.album?.id
                ?: restoredAlbumId?.takeIf { it.isNotBlank() }
                ?: error("LocalAlbumRadio has no album id")

    private val endpoint: WatchEndpoint
        get() =
            WatchEndpoint(
                playlistId = requireNotNull(playlistId) { "LocalAlbumRadio has no playlist id" },
                params = "wAEB",
            )

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(IO) {
            restoredStatus?.let { status ->
                restoredStatus = null
                return@withContext status
            }

            val source = requireNotNull(albumWithSongs) {
                "A restored LocalAlbumRadio must contain a restored queue snapshot"
            }
            initialSongCount = source.songs.size
            Queue.Status(
                title = source.album.title,
                items = source.songs.map { it.toMediaItem() },
                mediaItemIndex = startIndex,
            )
        }

    override fun hasNextPage(): Boolean = !firstTimeLoaded || continuation != null

    override suspend fun nextPage(): List<MediaItem> =
        withContext(IO) {
            if (!firstTimeLoaded) {
                if (playlistId.isNullOrBlank()) {
                    playlistId = YouTube.album(albumId).getOrThrow().album.playlistId
                }

                val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
                continuation = nextResult.continuation
                firstTimeLoaded = true
                return@withContext nextResult.items
                    .drop(initialSongCount.coerceAtLeast(0))
                    .map { it.toMediaItem() }
            }

            val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
            continuation = nextResult.continuation
            nextResult.items.map { it.toMediaItem() }
        }

    companion object {
        internal fun restore(
            albumId: String,
            playlistId: String?,
            continuation: String?,
            firstTimeLoaded: Boolean,
            initialSongCount: Int,
            status: Queue.Status,
        ): LocalAlbumRadio =
            LocalAlbumRadio(
                albumWithSongs = null,
                startIndex = status.mediaItemIndex,
                restoredAlbumId = albumId,
                playlistId = playlistId,
                continuation = continuation,
                firstTimeLoaded = firstTimeLoaded,
                restoredStatus = status,
                initialSongCount = initialSongCount.coerceAtLeast(0),
            )
    }
}
