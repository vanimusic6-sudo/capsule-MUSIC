package com.nikhil.yt.db

import android.app.Application
import androidx.room.Room
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.pages.ArtistPage
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ArtistSubscriptionTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val artist = ArtistEntity(id = "UC-artist", name = "Artist")
    private val bookmark = LocalDateTime.of(2026, 9, 12, 18, 0)
    private val page = ArtistPage(
        artist = ArtistItem(
            id = artist.id, title = "Updated artist", thumbnail = "https://example.com/portrait.jpg",
            shuffleEndpoint = null, radioEndpoint = null,
        ),
        sections = emptyList(),
        description = null,
    )

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun delayedProfileResponseCannotUndoTheFirstSubscription() {
        dao.insert(artist)
        val requestSnapshot = requireNotNull(dao.getArtistById(artist.id))
        val subscribed = requireNotNull(dao.setArtistBookmarked(artist, true))

        // Complete a metadata request that started before the tap.
        dao.update(requestSnapshot, page)

        val saved = requireNotNull(dao.getArtistById(artist.id))
        assertEquals(subscribed.bookmarkedAt, saved.bookmarkedAt)
        assertEquals(page.artist.title, saved.name)
        assertNotNull(saved.thumbnailUrl)
    }

    @Test fun delayedProfileResponseCannotRestoreAnUnsubscription() {
        val subscribed = artist.copy(bookmarkedAt = bookmark)
        dao.insert(subscribed)
        dao.setArtistBookmarked(subscribed, false)
        dao.update(subscribed, page)

        assertNull(dao.getArtistById(artist.id)?.bookmarkedAt)
        assertEquals(page.artist.title, dao.getArtistById(artist.id)?.name)
    }

    @Test fun subscribingAfterAnotherWriterInsertedTheArtistSucceedsAndPreservesMetadata() {
        // The screen has no library row yet, but a background writer inserts one first.
        val latest = artist.copy(name = "Latest name", channelId = "UC-channel", isLocal = true)
        dao.insert(latest)
        val changed = requireNotNull(dao.setArtistBookmarked(artist, true))

        assertNotNull(changed.bookmarkedAt)
        assertEquals(latest.copy(bookmarkedAt = changed.bookmarkedAt), dao.getArtistById(artist.id))
    }

    @Test fun repeatedSubscribeIntentBeforeUiRefreshDoesNotToggleBack() {
        val first = requireNotNull(dao.setArtistBookmarked(artist, true))
        assertNull(dao.setArtistBookmarked(artist, true))
        assertEquals(first, dao.getArtistById(artist.id))

        assertNotNull(dao.setArtistBookmarked(first, false))
        assertNull(dao.setArtistBookmarked(first, false))
        assertNull(dao.getArtistById(artist.id)?.bookmarkedAt)
    }

    @Test fun unsubscriptionWithAnOldUiSnapshotPreservesFreshProfileData() {
        val old = artist.copy(bookmarkedAt = bookmark)
        val latest = old.copy(name = "New name", thumbnailUrl = "new-photo", channelId = "new-channel")
        dao.insert(latest)
        dao.setArtistBookmarked(old, false)

        assertEquals(latest.copy(bookmarkedAt = null), dao.getArtistById(artist.id))
    }

    @Test fun oldSyncResponseCannotClearARecreatedSubscription() {
        dao.insert(artist.copy(bookmarkedAt = bookmark))
        dao.updateArtistBookmark(artist.id, bookmark.plusMinutes(1))

        assertEquals(0, dao.clearArtistBookmarkIfUnchanged(artist.id, bookmark))
        assertEquals(bookmark.plusMinutes(1), dao.getArtistById(artist.id)?.bookmarkedAt)
    }

    @Test fun syncCanClearAnUnchangedRemoteBookmarkButNeverALocalArtist() {
        dao.insert(artist.copy(bookmarkedAt = bookmark))
        assertEquals(1, dao.clearArtistBookmarkIfUnchanged(artist.id, bookmark))
        assertNull(dao.getArtistById(artist.id)?.bookmarkedAt)

        val local = artist.copy(id = "LA-local", isLocal = true, bookmarkedAt = bookmark)
        dao.insert(local)
        assertEquals(0, dao.clearArtistBookmarkIfUnchanged(local.id, bookmark))
        assertEquals(bookmark, dao.getArtistById(local.id)?.bookmarkedAt)
    }
}
