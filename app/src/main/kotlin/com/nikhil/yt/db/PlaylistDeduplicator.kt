package com.nikhil.yt.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep intentional repeated songs, and append occurrences missing from the surviving copy. */
internal fun missingPlaylistOccurrences(existing: List<String>, incoming: List<String>): List<Int> {
    val counts = existing.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return incoming.indices.filter { index ->
        val song = incoming[index]
        val occurrence = (seen[song] ?: 0) + 1
        seen[song] = occurrence
        occurrence > (counts[song] ?: 0)
    }
}

internal object PlaylistDeduplicator {
    fun merge(db: SupportSQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val groups = mutableListOf<String>()
            db.query("SELECT browseId FROM playlist WHERE browseId IS NOT NULL GROUP BY browseId HAVING COUNT(*) > 1").use {
                while (it.moveToNext()) groups += it.getString(0)
            }
            for (browseId in groups) {
                val ids = mutableListOf<String>()
                db.query(
                    "SELECT id FROM playlist WHERE browseId = ? ORDER BY " +
                        "(SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) DESC, rowid",
                    arrayOf(browseId),
                ).use { while (it.moveToNext()) ids += it.getString(0) }
                val target = ids.first()
                for (source in ids.drop(1)) mergeInto(db, target, source)
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_browseId ON playlist (browseId) WHERE browseId IS NOT NULL")
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun entries(db: SupportSQLiteDatabase, playlistId: String): List<Pair<Long, String>> =
        buildList {
            db.query("SELECT id, songId FROM playlist_song_map WHERE playlistId = ? ORDER BY position, id", arrayOf(playlistId)).use {
                while (it.moveToNext()) add(it.getLong(0) to it.getString(1))
            }
        }

    private fun mergeInto(db: SupportSQLiteDatabase, target: String, source: String) {
        val existing = entries(db, target)
        val incoming = entries(db, source)
        var position = db.query("SELECT COALESCE(MAX(position), -1) FROM playlist_song_map WHERE playlistId = ?", arrayOf(target)).use {
            it.moveToFirst()
            it.getInt(0) + 1
        }
        for (index in missingPlaylistOccurrences(existing.map { it.second }, incoming.map { it.second })) {
            db.execSQL("UPDATE playlist_song_map SET playlistId = ?, position = ? WHERE id = ?", arrayOf<Any>(target, position++, incoming[index].first))
        }
        db.execSQL(
            "INSERT OR IGNORE INTO playlist_tag_map (playlistId, tagId, createdAt) " +
                "SELECT ?, tagId, createdAt FROM playlist_tag_map WHERE playlistId = ?",
            arrayOf(target, source),
        )
        db.execSQL(
            "UPDATE playlist SET " +
                "bookmarkedAt = COALESCE(bookmarkedAt, (SELECT bookmarkedAt FROM playlist WHERE id = ?)), " +
                "thumbnailUrl = COALESCE(thumbnailUrl, (SELECT thumbnailUrl FROM playlist WHERE id = ?)), " +
                "isAutoSync = MAX(isAutoSync, (SELECT isAutoSync FROM playlist WHERE id = ?)) " +
                "WHERE id = ?",
            arrayOf(source, source, source, target),
        )
        db.execSQL("DELETE FROM playlist_tag_map WHERE playlistId = ?", arrayOf(source))
        db.execSQL("DELETE FROM playlist_song_map WHERE playlistId = ?", arrayOf(source))
        db.execSQL("DELETE FROM playlist WHERE id = ?", arrayOf(source))
    }
}
