package com.nikhil.yt.playback

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A failed write or replacement leaves the last committed snapshot readable. */
internal fun writePlaybackFileAtomically(
    destination: File,
    syncToDisk: Boolean,
    write: (FileOutputStream) -> Unit,
) {
    val temporary = File(destination.parentFile, "${destination.name}.tmp")
    try {
        FileOutputStream(temporary).use { stream ->
            write(stream)
            stream.flush()
            if (syncToDisk) stream.fd.sync()
        }
        // Both files are in the same directory. Never delete the committed file first.
        Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
