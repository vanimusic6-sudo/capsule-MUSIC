package com.nikhil.yt.viewmodels

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Restore untrusted archives into private, disposable staging files FIRST.
 * Never write user-selected ZIP entries into live databases or DataStore.
 * A single verified input pass also avoids reopening a mutable ContentProvider
 * URI after preflight (source-swap / time-of-check-time-of-use).
 */
internal object BackupRestoreStaging {
    private const val ROLLBACK_DIR = ".capsule-restore-rollback"
    private const val INSTALL_MARKER = ".installing"
    private const val COMPLETE_MARKER = ".complete"
    private const val MAX_ENTRIES = 8
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun databaseFiles(dbName: String): List<String> =
        listOf(dbName, "$dbName-wal", "$dbName-shm", "$dbName-journal")

    fun stage(
        authenticatedInput: InputStream,
        stageDir: File,
        databaseName: String,
        settingsXmlName: String,
        legacySettingsName: String,
    ): Set<String> {
        require(stageDir.isDirectory) { "Staging directory must exist" }
        val databaseFiles = databaseFiles(databaseName)
        val allowed = databaseFiles.toSet() + settingsXmlName + legacySettingsName
        val staged = LinkedHashSet<String>()
        var total = 0L
        // Always close the authenticated *inner* stream; encrypted backups
        // verify their final GCM record during close, even if ZIP parsing ends.
        ZipInputStream(authenticatedInput).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name !in allowed || name in staged ||
                    name.length > 100 || staged.size >= MAX_ENTRIES
                ) {
                    throw IOException("Unsupported, duplicate or unsafe backup entry")
                }
                staged.add(name)
                val limit = if (name == settingsXmlName || name == legacySettingsName) {
                    BackupSecurity.MAX_SETTINGS_BYTES
                } else {
                    BackupSecurity.MAX_DATABASE_BYTES
                }
                // Legacy settings.preferences_pb can contain old auth tokens;
                // never restore them, but still validate their uncompressed size.
                val target = if (name == legacySettingsName) null else File(stageDir, name)
                val sink = target?.outputStream() ?: object : java.io.OutputStream() {
                    override fun write(b: Int) = Unit
                    override fun write(b: ByteArray, off: Int, len: Int) = Unit
                }
                sink.use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var fileBytes = 0L
                    while (true) {
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > limit - fileBytes ||
                            count.toLong() > BackupSecurity.MAX_ARCHIVE_BYTES - total
                        ) throw IOException("Archive entry exceeds safe extraction limit")
                        out.write(buffer, 0, count)
                        total += count
                        fileBytes += count
                    }
                }
                archive.closeEntry()
            }
        }
        if (databaseName !in staged) throw IOException("Backup missing its database")
        val db = File(stageDir, databaseName)
        val header = ByteArray(SQLITE_MAGIC.size)
        val count = FileInputStream(db).use { input ->
            var read = 0
            while (read < header.size) {
                val next = input.read(header, read, header.size - read)
                if (next < 0) break
                read += next
            }
            read
        }
        if (count != header.size || !header.contentEquals(SQLITE_MAGIC)) {
            throw IOException("Backup does not contain a valid SQLite database header")
        }
        return staged
    }

    /**
     * Moving the old files out before installing any replacements makes a
     * restore recoverable if a process is killed during a filesystem move.
     * A two-phase marker distinguishes old-file moves from new-file installs.
     */
    fun installDatabase(stageDir: File, databaseDir: File, dbName: String) {
        val names = databaseFiles(dbName)
        val rollback = File(databaseDir, ROLLBACK_DIR)
        if (rollback.exists()) {
            recoverInterrupted(databaseDir, dbName)
            if (rollback.exists()) throw IOException("Could not recover a previous restore")
        }
        if (!rollback.mkdir()) throw IOException("Cannot create recovery directory")
        try {
            // Phase 1: save previous DB, WAL, shm and journal in the same
            // filesystem. Do not touch the new ZIP until all old files are safe.
            for (name in names) {
                val current = File(databaseDir, name)
                if (current.exists()) move(current, File(rollback, name))
            }
            File(rollback, INSTALL_MARKER).writeText("installing")
            // Phase 2: install only files from the fully authenticated staging.
            for (name in names) {
                val candidate = File(stageDir, name)
                if (candidate.exists()) move(candidate, File(databaseDir, name))
            }
            // After commit, a process crash during rollback cleanup must
            // never cause the next startup to discard the new valid database.
            File(rollback, COMPLETE_MARKER).writeText("committed")
            rollback.deleteRecursively()
        } catch (error: Exception) {
            runCatching { recoverInterrupted(databaseDir, dbName) }
                .onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    fun recoverInterrupted(databaseDir: File, dbName: String) {
        val rollback = File(databaseDir, ROLLBACK_DIR)
        if (!rollback.exists()) return
        val names = databaseFiles(dbName)
        if (File(rollback, COMPLETE_MARKER).exists()) {
            rollback.deleteRecursively()
            return
        }
        // Delete new DB files only AFTER the marker was written. Before it
        // exists, files not yet moved to rollback still belong to the old DB.
        if (File(rollback, INSTALL_MARKER).exists()) {
            for (name in names) File(databaseDir, name).delete()
        }
        for (name in names) {
            val previous = File(rollback, name)
            if (previous.exists()) move(previous, File(databaseDir, name))
        }
        if (!rollback.deleteRecursively()) {
            throw IOException("Cannot clear interrupted database restore")
        }
    }

    private fun move(from: File, to: File) {
        // All paths live in the application's private storage, on the same
        // /data partition. Never copy an untrusted archive over the live DB.
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
