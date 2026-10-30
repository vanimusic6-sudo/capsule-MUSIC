package com.nikhil.yt.viewmodels

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestoreStagingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dbName = "capsule.db"
    private val sqlite = "SQLite format 3\u0000".toByteArray() + ByteArray(128) { 0x30 }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { archive ->
                for ((name, contents) in entries) {
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(contents)
                    archive.closeEntry()
                }
            }
        }.toByteArray()

    private fun extract(bytes: ByteArray, stage: File): Set<String> =
        BackupRestoreStaging.stage(
            ByteArrayInputStream(bytes), stage, dbName, "settings.xml", "settings.preferences_pb",
        )

    @Test fun rejectsTraversalUnknownAndMissingDatabaseBeforeInstalling() {
        val stage = temporary.newFolder("stage")
        for (archive in listOf(
            zip("../capsule.db" to sqlite),
            zip("notes.txt" to sqlite),
            zip("settings.xml" to byteArrayOf(1)),
        )) {
            assertThrows(IOException::class.java) { extract(archive, stage) }
        }
    }

    @Test fun refusesInvalidSqliteAndOversizedSetting() {
        val stage = temporary.newFolder("stage")
        assertThrows(IOException::class.java) { extract(zip(dbName to byteArrayOf(1,2,3)), stage) }
        assertThrows(IOException::class.java) {
            extract(zip(dbName to sqlite,
                "settings.xml" to ByteArray((BackupSecurity.MAX_SETTINGS_BYTES + 1).toInt())
            ), stage)
        }
    }

    @Test fun stageThenInstallReplacesDatabaseAndDiscardsLegacyTokenFile() {
        val stage = temporary.newFolder("stage")
        val dbDir = temporary.newFolder("databases")
        File(dbDir, dbName).writeBytes(sqlite + byteArrayOf(5, 6))
        File(dbDir, "$dbName-wal").writeBytes(byteArrayOf(7))
        val next = sqlite + byteArrayOf(3, 4)
        val names = extract(zip(
            dbName to next,
            "settings.xml" to "<Settings/>".toByteArray(),
            "settings.preferences_pb" to "legacy-secret".toByteArray(),
        ), stage)
        assertTrue(dbName in names)
        assertFalse(File(stage, "settings.preferences_pb").exists())
        BackupRestoreStaging.installDatabase(stage, dbDir, dbName)
        assertArrayEquals(next, File(dbDir, dbName).readBytes())
        assertFalse(File(dbDir, "$dbName-wal").exists())
        assertFalse(File(dbDir, ".capsule-restore-rollback").exists())
    }

    @Test fun interruptedInstallIsRolledBackAtStartupAndCommitIsPreserved() {
        val dbDir = temporary.newFolder("db")
        val rollback = File(dbDir, ".capsule-restore-rollback").apply { mkdir() }
        val old = sqlite + byteArrayOf(9)
        File(rollback, dbName).writeBytes(old)
        File(rollback, ".installing").writeText("installing")
        File(dbDir, dbName).writeBytes(sqlite + byteArrayOf(8))
        BackupRestoreStaging.recoverInterrupted(dbDir, dbName)
        assertArrayEquals(old, File(dbDir, dbName).readBytes())

        rollback.mkdir()
        File(rollback, dbName).writeBytes(old)
        File(rollback, ".installing").writeText("installing")
        File(rollback, ".complete").writeText("committed")
        val updated = sqlite + byteArrayOf(1)
        File(dbDir, dbName).writeBytes(updated)
        BackupRestoreStaging.recoverInterrupted(dbDir, dbName)
        assertArrayEquals(updated, File(dbDir, dbName).readBytes())
        assertFalse(rollback.exists())
    }
}
