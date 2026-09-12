package com.nikhil.yt.db

import android.app.Application
import android.database.SQLException
import androidx.room.Room
import com.nikhil.yt.db.entities.Event
import com.nikhil.yt.models.MediaMetadata
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class PlaybackHistoryTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val snapshot = MediaMetadata(
        id = "finished-song",
        title = "Finished song",
        artists = listOf(MediaMetadata.Artist("artist", "Artist")),
        duration = 180,
    )
    private val event = Event(
        songId = snapshot.id,
        timestamp = LocalDateTime.of(2026, 9, 5, 18, 23),
        playTime = 45_000L,
    )

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun finishedSnapshotSavesHistoryWhenMetadataRecoveryNeverInsertedTheSong() = runBlocking {
        // Reproduce the reported constraint with the actual Room-generated DAO.
        assertThrows(SQLException::class.java) { dao.insert(event) }

        assertTrue(dao.recordPlayback(event, snapshot))
        val saved = requireNotNull(dao.getSongByIdBlocking(snapshot.id))
        assertEquals(snapshot.title, saved.song.title)
        assertEquals("Artist", saved.artists.single().name)
        assertEquals(event.playTime, saved.song.totalPlayTime)
        assertNull(saved.song.dateDownload)
        assertEquals(event.songId, dao.events().first().single().event.songId)
    }

    @Test fun recordingExistingSongPreservesLibraryLikesAndDownloads() = runBlocking {
        val date = event.timestamp.minusDays(1)
        val existing = snapshot.toSongEntity().copy(
            title = "Saved title", liked = true, likedDate = date,
            inLibrary = date, dateDownload = date, totalPlayTime = 100L,
        )
        dao.insert(existing)

        assertTrue(dao.recordPlayback(event, snapshot))
        assertTrue(dao.recordPlayback(event.copy(timestamp = event.timestamp.plusMinutes(1)), null))

        assertEquals(
            existing.copy(totalPlayTime = 100L + 2 * event.playTime),
            dao.getSongByIdBlocking(snapshot.id)?.song,
        )
        assertEquals(2, dao.events().first().size)
    }

    @Test fun deletedSongCanBeRecordedFromTheFinishedItemSnapshot() = runBlocking {
        dao.insert(snapshot)
        database.openHelper.writableDatabase.execSQL("DELETE FROM song")

        assertTrue(dao.recordPlayback(event, snapshot))
        assertEquals(event.playTime, dao.getSongByIdBlocking(snapshot.id)?.song?.totalPlayTime)
        assertEquals(1, dao.events().first().size)
    }

    @Test fun absentOrWrongSnapshotDoesNotInventAParentOrSaveAnOrphanEvent() = runBlocking {
        assertFalse(dao.recordPlayback(event, null))
        assertFalse(dao.recordPlayback(event, snapshot.copy(id = "next-song")))
        assertNull(dao.getSongByIdBlocking(snapshot.id))
        assertNull(dao.getSongByIdBlocking("next-song"))
        assertTrue(dao.events().first().isEmpty())
    }

    @Test fun failedEventInsertRollsBackBothPlayTimeAndNewMetadata() = runBlocking {
        val existing = snapshot.toSongEntity().copy(totalPlayTime = 100L, dateDownload = event.timestamp)
        dao.insert(existing)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_history BEFORE INSERT ON event " +
                "BEGIN SELECT RAISE(ABORT, 'test history failure'); END",
        )

        assertThrows(SQLException::class.java) { dao.recordPlayback(event, snapshot) }
        assertEquals(existing, dao.getSongByIdBlocking(snapshot.id)?.song)

        val newSong = snapshot.copy(id = "new-song")
        assertThrows(SQLException::class.java) {
            dao.recordPlayback(event.copy(songId = newSong.id), newSong)
        }
        assertNull(dao.getSongByIdBlocking(newSong.id))
        assertTrue(dao.events().first().isEmpty())
    }
}
