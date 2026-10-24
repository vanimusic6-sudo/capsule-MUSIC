package com.nikhil.yt.discovery

import android.app.Application
import androidx.work.Data
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class IndependentAudioDownloadWorkerTest {
    private fun track(downloadable: Boolean) = ExperimentalCatalog.Track(
        source = ExperimentalCatalog.Source.AUDIUS, id = "abc", title = "Song", artist = "Artist",
        artwork = "https://images.example/art.jpg", streamUrl = "https://api.audius.co/v1/tracks/abc/stream",
        sourcePage = "https://audius.co/artist/song", downloadable = downloadable,
    )

    @Test fun durableInputPreservesCreditsAndPublisherPermission() {
        val original = track(true)
        val input = IndependentAudioDownloadWorker.input(original, "Artist - Song.mp3", "audio/mpeg")
        val restored = requireNotNull(IndependentAudioDownloadWorker.track(input))
        assertEquals(original.mediaId, restored.mediaId)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.downloadUrl, restored.downloadUrl)
        assertEquals(original.artwork, restored.artwork)
    }

    @Test fun queueDoesNotTurnStreamingPermissionIntoDownloadPermission() {
        val original = track(false)
        val restored = IndependentAudioDownloadWorker.track(
            IndependentAudioDownloadWorker.input(original, "Song.mp3", "audio/mpeg"),
        )
        assertNotNull(restored)
        assertNull(restored?.downloadUrl)
    }

    @Test fun aRestoredCompletedUriCannotShadowTheCurrentWorkersPendingUri() {
        val preferences = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("work-reference-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear()
            .putString("completed:track", "content://media/external/downloads/10")
            .putString("completed-owner:track", "previous-installation-work")
            .putString("pending:track", "content://media/external/downloads/11")
            .putString("pending-owner:track", "current-work").commit()
        assertEquals("content://media/external/downloads/11",
            ExperimentalDownload.ownedWorkReference(preferences, "track", "current-work").toString())
        assertNull(ExperimentalDownload.ownedWorkReference(preferences, "track", "unrelated-work"))
        assertEquals("content://media/external/downloads/10", preferences.getString("completed:track", null))
    }

    @Test fun publishedFileCanOnlyBeAcknowledgedByItsOwningDurableWork() {
        val preferences = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("work-reference-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear()
            .putString("completed:track", "content://media/external/downloads/12")
            .putString("completed-owner:track", "same-work").commit()
        assertEquals("content://media/external/downloads/12",
            ExperimentalDownload.ownedWorkReference(preferences, "track", "same-work").toString())
        assertNull(ExperimentalDownload.ownedWorkReference(preferences, "track", "restored-new-work"))
    }

    @Test fun malformedPersistedRequestFailsClosed() {
        assertNull(IndependentAudioDownloadWorker.track(Data.EMPTY))
    }
}
