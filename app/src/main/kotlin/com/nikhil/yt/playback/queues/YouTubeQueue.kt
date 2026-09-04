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

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    private var continuation: String? = null,
    private var restoredStatus: Queue.Status? = null,
) : Queue {
    internal data class PersistenceState(
        val endpoint: WatchEndpoint,
        val continuation: String?,
    )

    internal fun persistenceState(): PersistenceState =
        PersistenceState(
            endpoint = endpoint,
            continuation = continuation,
        )

    override suspend fun getInitialStatus(): Queue.Status {
        /*
         * A restored process already has the exact timeline in PersistQueue.
         * Return it once instead of re-fetching page one, while retaining the
         * saved endpoint/continuation for the next real pagination request.
         */
        restoredStatus?.let { status ->
            restoredStatus = null
            return status
        }

        val nextResult =
            withContext(IO) {
                YouTube.next(endpoint, continuation).getOrThrow()
            }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return Queue.Status(
            title = nextResult.title,
            items = nextResult.items.map { it.toMediaItem() },
            mediaItemIndex = nextResult.currentIndex ?: 0,
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        val nextResult =
            withContext(IO) {
                YouTube.next(endpoint, continuation).getOrThrow()
            }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return nextResult.items.map { it.toMediaItem() }
    }

    companion object {
        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
