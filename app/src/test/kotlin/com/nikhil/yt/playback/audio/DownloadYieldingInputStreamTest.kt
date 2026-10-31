package com.nikhil.yt.playback.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadYieldingInputStreamTest {
    @Test fun independentDownloadYieldsBetweenReadBoundariesWithoutLosingBytes() {
        val content = ByteArray(128 * 1024) { (it % 251).toByte() }
        var checks = 0
        var triggeredYield = false
        val output = ByteArrayOutputStream(content.size)
        DownloadYieldingInputStream(ByteArrayInputStream(content)) {
            checks++
            // Simulate a short foreground buffering period after the first 64 KiB.
            if (!triggeredYield) {
                triggeredYield = true
                true
            } else false
        }.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        assertTrue(checks >= 2)
        assertEquals(content.size, output.size())
        assertArrayEquals(content, output.toByteArray())
    }
}
