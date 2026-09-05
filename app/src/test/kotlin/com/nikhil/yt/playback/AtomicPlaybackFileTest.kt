package com.nikhil.yt.playback

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicPlaybackFileTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun failedWritePreservesTheLastRestorableQueue() {
        val file = directory.newFile("queue").apply { writeText("old queue") }
        try {
            writePlaybackFileAtomically(file, true) { it.write("partial".toByteArray()); throw IOException("disk full") }
            fail("Expected write failure")
        } catch (_: IOException) { }
        assertEquals("old queue", file.readText())
        assertFalse(File(file.parentFile, "queue.tmp").exists())
    }

    @Test fun completedWriteReplacesTheQueue() {
        val file = directory.newFile("queue").apply { writeText("old queue") }
        writePlaybackFileAtomically(file, true) { it.write("new queue".toByteArray()) }
        assertEquals("new queue", file.readText())
    }
}
