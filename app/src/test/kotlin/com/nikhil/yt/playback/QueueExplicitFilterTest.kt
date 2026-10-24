package com.nikhil.yt.playback

import android.app.Application
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.playback.queues.Queue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class QueueExplicitFilterTest {
    @Test fun filteringEarlierSongsKeepsTheChosenTrackAndSeekPosition() {
        val queue = Queue.Status("queue", listOf(item("x", true), item("a"), item("b")), 2, 1500)
        val filtered = queue.filterExplicit()
        assertEquals(listOf("a", "b"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex); assertEquals(1500L, filtered.position)
    }
    @Test fun filteringTheSelectedTrackChoosesTheNextRetainedSongAndClearsItsPosition() {
        val queue = Queue.Status(null, listOf(item("a"), item("x", true), item("b")), 1, 1500)
        val filtered = queue.filterExplicit()
        assertEquals("b", filtered.items[filtered.mediaItemIndex].mediaId)
        assertEquals(0L, filtered.position)
        assertSame(queue, queue.filterExplicit(false))
    }
    @Test fun emptyAndAllExplicitListsHaveABoundedStartIndex() {
        val filtered = Queue.Status(null, listOf(item("x", true)), 0, 10).filterExplicit()
        assertTrue(filtered.items.isEmpty()); assertEquals(0, filtered.mediaItemIndex)
        assertEquals(0L, filtered.position)
    }
    private fun item(id: String, explicit: Boolean = false) = MediaMetadata(
        id = id, title = id, artists = emptyList(), duration = 10, thumbnailUrl = null, explicit = explicit,
    ).toMediaItem()
}
