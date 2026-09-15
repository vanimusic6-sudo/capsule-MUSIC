package com.nikhil.yt.db

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseRecoveryBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun snapshotPreservesDatabaseAndUncheckpointedWalWithoutChangingTheOriginal() {
        val database = temporary.newFile("song.db").apply { writeText("original database") }
        val wal = File(database.path + "-wal").apply { writeText("recent playlists") }
        val directory = temporary.newFolder("recovery")
        val snapshot = requireNotNull(DatabaseRecoveryBackup.create(database, directory))
        assertEquals(database.readText(), File(snapshot, "song.db").readText())
        assertEquals(wal.readText(), File(snapshot, "song.db-wal").readText())
        assertEquals("original database", database.readText())
        assertEquals("recent playlists", wal.readText())
        assertEquals(listOf(snapshot), directory.listFiles()!!.toList())
    }

    @Test fun failedBackupLeavesOriginalDataIntact() {
        val database = temporary.newFile("song.db").apply { writeText("keep this") }
        val blocked = temporary.newFile("not-a-directory")
        assertTrue(runCatching { DatabaseRecoveryBackup.create(database, blocked) }.isFailure)
        assertEquals("keep this", database.readText())
    }

    @Test fun upgradesAndDowngradesAreDetectedBeforeRoomOpensTheFile() {
        val database = temporary.newFile("song.db")
        RandomAccessFile(database, "rw").use { it.seek(60L); it.writeInt(27) }
        assertTrue(DatabaseRecoveryBackup.needsMigration(database, 28))
        assertTrue(DatabaseRecoveryBackup.needsMigration(database, 26))
        assertFalse(DatabaseRecoveryBackup.needsMigration(database, 27))
    }
}
