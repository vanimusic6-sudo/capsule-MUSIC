package com.nikhil.yt.db

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Called before Room opens the database, while this process has no database writers. */
internal object DatabaseRecoveryBackup {
    fun needsMigration(database: File, targetVersion: Int): Boolean {
        if (!database.isFile || database.length() == 0L) return false
        // SQLite's user_version is a big-endian integer at offset 60. If WAL has a
        // newer version, an extra backup is harmless; never open/migrate just to check.
        return runCatching {
            RandomAccessFile(database, "r").use { file ->
                file.seek(60L)
                file.readInt() != targetVersion
            }
        }.getOrDefault(true)
    }

    fun create(database: File, directory: File): File? {
        if (!database.isFile) return null
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create database recovery directory" }
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val staging = File(directory, ".$id.partial")
        check(staging.mkdir()) { "Cannot create database recovery snapshot" }
        try {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val source = File(database.path + suffix)
                if (!source.isFile) continue
                source.inputStream().use { input ->
                    FileOutputStream(File(staging, database.name + suffix)).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            val completed = File(directory, id)
            check(staging.renameTo(completed)) { "Cannot finish database recovery snapshot" }
            return completed
        } catch (failure: Exception) {
            staging.deleteRecursively()
            throw failure
        }
    }
}
