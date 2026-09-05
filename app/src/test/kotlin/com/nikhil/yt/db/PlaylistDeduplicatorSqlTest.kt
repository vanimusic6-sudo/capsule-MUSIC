package com.nikhil.yt.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production merger against real SQLite, including foreign keys and rollback. */
class PlaylistDeduplicatorSqlTest {
    @Test fun mergesSongsOccurrencesTagsAndBookmarkBeforeDeletingACopy() = database { connection, db ->
        PlaylistDeduplicator.merge(db)
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM playlist"))
        assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM playlist_song_map"))
        assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM playlist_song_map WHERE songId = 'a'"))
        assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM playlist_tag_map WHERE playlistId = 'B'"))
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM playlist WHERE bookmarkedAt IS NOT NULL"))
        PlaylistDeduplicator.merge(db)
        assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM playlist_song_map"))
        assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun failureDuringDeletionRollsBackMovedSongsAndTags() = database { connection, db ->
        connection.createStatement().use {
            it.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON playlist BEGIN SELECT RAISE(ABORT, 'Injected disk failure'); END")
        }
        assertTrue(runCatching { PlaylistDeduplicator.merge(db) }.isFailure)
        assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM playlist"))
        assertEquals(7, scalar(connection, "SELECT COUNT(*) FROM playlist_song_map"))
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM playlist_tag_map WHERE playlistId = 'A'"))
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM playlist_tag_map WHERE playlistId = 'B'"))
    }

    @Test fun legacyMembershipExpressionCopiesTheOriginalTimestamp() = database { connection, _ ->
        connection.createStatement().use {
            it.execute("CREATE TABLE old_song(id TEXT, createDate INTEGER)")
            it.execute("INSERT INTO old_song VALUES ('saved', 123456)")
            it.execute("CREATE TABLE new_song(id TEXT, inLibrary INTEGER)")
            val expression = requireNotNull(legacyLibraryValue("song", "inLibrary", setOf("id", "createDate")))
            it.execute("INSERT INTO new_song SELECT id, $expression FROM old_song")
        }
        assertEquals(123456, scalar(connection, "SELECT inLibrary FROM new_song"))
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM new_song WHERE inLibrary IS NOT NULL"))
    }

    @Test fun oldPlaylistsWithoutTimestampColumnsRemainBookmarked() = database { connection, _ ->
        connection.createStatement().use {
            it.execute("CREATE TABLE old_playlist(id TEXT, browseId TEXT)")
            it.execute("INSERT INTO old_playlist VALUES ('LPsaved', 'remote')")
            it.execute("CREATE TABLE new_playlist(id TEXT, bookmarkedAt INTEGER)")
            val expression = requireNotNull(legacyLibraryValue("playlist", "bookmarkedAt", setOf("id", "browseId")))
            it.execute("INSERT INTO new_playlist SELECT id, $expression FROM old_playlist")
        }
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM new_playlist WHERE bookmarkedAt > 0"))
    }

    private fun database(block: (Connection, SupportSQLiteDatabase) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "PRAGMA foreign_keys=ON",
                    "CREATE TABLE playlist(id TEXT PRIMARY KEY, browseId TEXT, bookmarkedAt INTEGER, thumbnailUrl TEXT, isAutoSync INTEGER NOT NULL DEFAULT 0)",
                    "CREATE TABLE playlist_song_map(id INTEGER PRIMARY KEY, playlistId TEXT REFERENCES playlist(id) ON DELETE CASCADE, songId TEXT, position INTEGER)",
                    "CREATE TABLE playlist_tag_map(playlistId TEXT, tagId TEXT, createdAt INTEGER, PRIMARY KEY(playlistId, tagId))",
                    "INSERT INTO playlist VALUES ('A','remote',1,NULL,0),('B','remote',NULL,NULL,1)",
                    "INSERT INTO playlist_song_map VALUES (1,'A','a',0),(2,'A','a',1),(3,'A','b',2),(4,'B','a',0),(5,'B','a',1),(6,'B','a',2),(7,'B','c',3)",
                    "INSERT INTO playlist_tag_map VALUES ('A','tag1',1),('B','tag2',2)",
                ).forEach(statement::execute)
            }
            block(connection, sqliteAdapter(connection))
        }
    }

    private fun scalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { it.next(); it.getInt(1) }
    }

    /** Only adapts Android's database interfaces; all SQL is executed by SQLite itself. */
    private fun sqliteAdapter(connection: Connection): SupportSQLiteDatabase {
        var successful = false
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "inTransaction" -> !connection.autoCommit
                "beginTransaction" -> { connection.autoCommit = false; successful = false; null }
                "setTransactionSuccessful" -> { successful = true; null }
                "endTransaction" -> {
                    if (successful) connection.commit() else connection.rollback()
                    connection.autoCommit = true
                    null
                }
                "execSQL", "query" -> connection.prepareStatement(args!![0] as String).use { statement ->
                    (args.getOrNull(1) as? Array<*>)?.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    if (method.name == "execSQL") { statement.execute(); null }
                    else statement.executeQuery().use { results ->
                        val rows = mutableListOf<List<Any?>>()
                        while (results.next()) rows += (1..results.metaData.columnCount).map(results::getObject)
                        var row = -1
                        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Cursor::class.java)) { _, call, values ->
                            when (call.name) {
                                "moveToNext" -> { row++; row < rows.size }
                                "moveToFirst" -> { row = 0; rows.isNotEmpty() }
                                "getString" -> rows[row][values!![0] as Int]?.toString()
                                "getInt" -> (rows[row][values!![0] as Int] as Number).toInt()
                                "getLong" -> (rows[row][values!![0] as Int] as Number).toLong()
                                "close" -> null
                                else -> error("Unexpected cursor operation ${call.name}")
                            }
                        } as Cursor
                    }
                }
                else -> error("Unexpected database operation ${method.name}")
            }
        } as SupportSQLiteDatabase
    }
}
