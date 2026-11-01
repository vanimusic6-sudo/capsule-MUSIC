package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.pages.PlaylistContinuationPage
import com.nikhil.yt.models.MediaMetadata

/** Start with already displayed tracks; request only genuine continuation pages.
 * Shuffle is progressive: shuffle the loaded batch and each later batch once.
 */
class LoadedPlaylistQueue(
    private val title: String?,
    private val items: List<MediaItem>,
    private var continuation: String?,
    private val shuffle: Boolean = false,
    private var restoredStatus: Queue.Status? = null,
    private val fetch: suspend (String) -> Result<PlaylistContinuationPage> = { YouTube.playlistContinuation(it) },
) : Queue {
    override val preloadItem: MediaMetadata? = null
    private val seenIds = items.mapTo(mutableSetOf()) { it.mediaId }
    private val consumed = mutableSetOf<String>()

    internal fun continuation() = continuation
    internal fun shufflesPages() = shuffle

    override suspend fun getInitialStatus(): Queue.Status {
        restoredStatus?.let { restoredStatus = null; return it }
        return Queue.Status(title, if (shuffle) items.shuffled() else items, 0)
    }

    override fun hasNextPage() = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        val token = continuation ?: return emptyList()
        if (token in consumed) { continuation = null; return emptyList() }
        // Do not consume a cursor on failure/cancellation: the player may retry.
        val page = fetch(token).getOrThrow()
        consumed += token
        continuation = page.continuation?.takeUnless { it in consumed }
        val next = page.songs.filter { seenIds.add(it.id) }.map { it.toMediaItem() }
        return if (shuffle) next.shuffled() else next
    }
}
