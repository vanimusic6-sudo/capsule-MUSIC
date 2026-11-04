/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.toMediaItem
import timber.log.Timber
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * YouTube's watch-next response may contain a selected radio/suggestion rather
 * than the video the user just tapped (especially OMV/UGC in an artist carousel).
 * The requested item is authoritative. Network results may add upcoming tracks,
 * but must NEVER replace the current selection, even if /next finishes after
 * the chosen video has already started buffering or playing.
 */
internal fun anchorYouTubeQueueSelection(
    status: Queue.Status,
    requestedVideoId: String?,
    preloadItem: MediaMetadata?,
): Queue.Status {
    val requestedId = preloadItem?.id?.takeIf { it.isNotBlank() }
        ?: requestedVideoId?.takeIf { it.isNotBlank() }
        ?: return status
    val requestedIndex = status.items.indexOfFirst { it.mediaId == requestedId }

    if (requestedIndex >= 0) {
        if (preloadItem == null) return status.copy(mediaItemIndex = requestedIndex, position = 0L)
        val items = status.items.toMutableList()
        // A /next renderer is often an AUDIO-only projection even though the
        // originally tapped card was explicitly an original YouTube video.
        // Keep its metadata/video flag and native artwork with the selection.
        items[requestedIndex] = preloadItem.toMediaItem()
        return status.copy(items = items, mediaItemIndex = requestedIndex, position = 0L)
    }

    if (preloadItem != null) {
        // Server omitted the requested video entirely. Keep it as item zero
        // and treat the unrelated returned entries as optional successors.
        // Never turn a slow /next result into an unrelated playback choice.
        return status.copy(
            items = listOf(preloadItem.toMediaItem()) + status.items,
            mediaItemIndex = 0,
            position = 0L,
        )
    }

    // Some endpoints are playlist/radio requests with no locally known item.
    // Preserve their original selection instead of constructing fake metadata.
    return status
}

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

    internal fun takeOfflineStatus(): Queue.Status {
        restoredStatus?.let { restoredStatus = null; return it }
        return Queue.Status(preloadItem?.title, listOfNotNull(preloadItem?.toMediaItem()), 0)
    }

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

        val requestedVideoId = endpoint.videoId
        val nextResult =
            withContext(IO) {
                YouTube.next(endpoint, continuation).getOrThrow()
            }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        val networkStatus = Queue.Status(
            title = nextResult.title,
            items = nextResult.items.map { it.toMediaItem() },
            mediaItemIndex = nextResult.currentIndex ?: 0,
        )
        val anchored = anchorYouTubeQueueSelection(networkStatus, requestedVideoId, preloadItem)
        val networkChoice = networkStatus.items.getOrNull(networkStatus.mediaItemIndex)?.mediaId
        val anchoredChoice = anchored.items.getOrNull(anchored.mediaItemIndex)?.mediaId
        if (anchoredChoice != networkChoice) {
            Timber.tag("QueueSelection").w(
                "Watch-next selected different item; kept tapped video id=%s networkId=%s",
                anchoredChoice ?: "none", networkChoice ?: "none",
            )
        }
        return anchored
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
