package com.nikhil.yt.playback

import android.app.Application
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.extensions.toPersistQueue
import com.nikhil.yt.extensions.toQueue
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.pages.PlaylistContinuationPage
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.persistableList
import com.nikhil.yt.playback.queues.LoadedPlaylistQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoadedPlaylistQueueTest {
    private fun metadata(id: String) = MediaMetadata(id, id, emptyList(), 100)
    private fun song(id: String) = SongItem(id, id, emptyList(), thumbnail = "")

    @Test fun playAndShuffleStartFromLoadedItemsWithoutARequest() = runBlocking {
        for (shuffle in listOf(false, true)) {
            val items = (1..20).map { metadata("track-$it").toMediaItem() }
            val queue = LoadedPlaylistQueue("Playlist", items, "next", shuffle) { error("Initial play must not fetch") }
            assertEquals(items.map { it.mediaId }.toSet(), queue.getInitialStatus().items.map { it.mediaId }.toSet())
            assertTrue(queue.hasNextPage())
        }
    }

    @Test fun pagesAppendWithoutRepeatsAndRepeatedCursorsStop() = runBlocking {
        var requests = 0
        val queue = LoadedPlaylistQueue("Playlist", listOf(metadata("a").toMediaItem()), "next") {
            requests++
            Result.success(PlaylistContinuationPage(listOf(song("a"), song("b"), song("b")), "next"))
        }
        queue.getInitialStatus()
        assertEquals(listOf("b"), queue.nextPage().map { it.mediaId })
        assertFalse(queue.hasNextPage())
        assertTrue(queue.nextPage().isEmpty())
        assertEquals(1, requests)
    }

    @Test fun cancellationDoesNotConsumeThePendingPage() = runBlocking {
        var cancelled = true
        val queue = LoadedPlaylistQueue(null, emptyList(), "next") {
            if (cancelled) throw CancellationException("Skipped")
            Result.success(PlaylistContinuationPage(listOf(song("b")), null))
        }
        try { queue.nextPage(); fail("Cancellation expected") } catch (_: CancellationException) { }
        assertTrue(queue.hasNextPage())
        cancelled = false
        assertEquals(listOf("b"), queue.nextPage().map { it.mediaId })
    }

    @Test fun existingSafePersistenceKeepsTheTimelineCursorAndShuffleMode() = runBlocking {
        val tracks = persistableList(listOf(metadata("a"), metadata("b")))
        val queue = LoadedPlaylistQueue("Playlist", tracks.map { it.toMediaItem() }, "cursor", true)
        val snapshot = queue.toPersistQueue("Playlist", tracks, 1, 9000L)
        val bytes = ByteArrayOutputStream().also { output -> ObjectOutputStream(output).use { it.writeObject(snapshot) } }.toByteArray()
        val restored = SafeQueueObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as com.nikhil.yt.models.PersistQueue }
        val resumed = restored.toQueue() as LoadedPlaylistQueue
        val status = resumed.getInitialStatus()
        assertEquals(listOf("a", "b"), status.items.map { it.mediaId })
        assertEquals(1, status.mediaItemIndex)
        assertEquals(9000L, status.position)
        assertEquals("cursor", resumed.continuation())
        assertTrue(resumed.shufflesPages())
    }
}
