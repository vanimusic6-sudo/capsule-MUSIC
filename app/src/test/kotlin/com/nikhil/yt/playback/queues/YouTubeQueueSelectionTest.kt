package com.nikhil.yt.playback.queues

import android.app.Application
import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.ExtraIsMusicVideo
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playback identity is selected by the tapped card, NOT YouTube's radio selection flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class YouTubeQueueSelectionTest {
    private fun track(id: String, video: Boolean = false) = MediaMetadata(
        id = id,
        title = "Title $id",
        artists = emptyList(),
        duration = 150,
        thumbnailUrl = "https://example.org/$id.jpg",
        isOriginalVideo = video,
    )
    private fun source(id: String) = track(id).toMediaItem()
    private fun ids(result: Queue.Status) = result.items.map { it.mediaId }

    @Test fun delayedNextNeverSwitchesToAnotherSelectedSongWhenVideoExists() {
        val video = track("selected-omv", video = true)
        val status = Queue.Status("Watch next",
            listOf(source("random-1"), source("selected-omv"), source("random-2")), 0)
        val result = anchorYouTubeQueueSelection(status, "selected-omv", video)
        assertEquals(1, result.mediaItemIndex)
        assertEquals("selected-omv", result.items[result.mediaItemIndex].mediaId)
        assertTrue(result.items[result.mediaItemIndex].metadata?.isOriginalVideo == true)
        assertEquals(0L, result.position)
        assertEquals(listOf("random-1", "selected-omv", "random-2"), ids(result))
        assertEquals(listOf("random-1", "selected-omv", "random-2"), ids(status))
    }

    @Test fun missingVideoInNextResponseCannotEraseAlreadyStartedVideo() {
        val video = track("selected-ugc", video = true)
        val status = Queue.Status("Radio", listOf(source("totally-unrelated"), source("another")), 1)
        val result = anchorYouTubeQueueSelection(status, "selected-ugc", video)
        assertEquals(0, result.mediaItemIndex)
        assertEquals(listOf("selected-ugc", "totally-unrelated", "another"), ids(result))
        assertTrue(result.items.first().metadata?.isOriginalVideo == true)
    }

    @Test fun emptyWatchNextStillKeepsTheTappedVideo() {
        val video = track("one-video", video = true)
        val result = anchorYouTubeQueueSelection(
            Queue.Status(null, emptyList(), 0), video.id, video,
        )
        assertEquals(listOf(video.id), ids(result))
        assertEquals(0, result.mediaItemIndex)
    }

    @Test fun selectedVideoKeepsItsMedia3VideoMetadataEvenIfNetworkSaysAudio() {
        val video = track("omv", video = true)
        val result = anchorYouTubeQueueSelection(
            Queue.Status(null, listOf(source("omv")), 0),
            requestedVideoId = "omv", preloadItem = video,
        )
        val item = result.items.single()
        assertTrue(item.metadata?.isOriginalVideo == true)
        assertTrue(item.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo) == true)
    }

    @Test fun plainSongAndPlaylistWithoutPreloadKeepNormalSelectionSemantics() {
        val a = source("song-a")
        val b = source("song-b")
        val status = Queue.Status("Album", listOf(a,b), 1)
        val explicit = anchorYouTubeQueueSelection(status, "song-a", null)
        assertEquals(0, explicit.mediaItemIndex)
        assertFalse(explicit.items[0].metadata?.isOriginalVideo == true)
        val playlist = anchorYouTubeQueueSelection(status, null, null)
        assertEquals(1, playlist.mediaItemIndex)
        assertEquals(ids(status), ids(playlist))
        assertEquals(status, playlist)
    }

    @Test fun mismatchedVideoCannotBeInjectedIntoAPlainRadioWithoutPreload() {
        val status = Queue.Status("Radio", listOf(source("random")), 0)
        assertEquals(status, anchorYouTubeQueueSelection(status, "missing-id", null))
    }
}
