package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class AudioNetworkDiagnosticDataSourceTest {
    @Test
    fun wrappedInterruptedIoIsExpectedInterruption() {
        val failure = IOException("Media3 wrapper", InterruptedIOException())
        assertTrue(failure.isExpectedAudioCdnInterruption())
    }

    @Test
    fun socketTimeoutRemainsARealFailure() {
        assertFalse(SocketTimeoutException("timeout").isExpectedAudioCdnInterruption())
    }

    @Test
    fun genericIoRemainsARealFailure() {
        assertFalse(IOException("connection reset").isExpectedAudioCdnInterruption())
    }

    @Test
    fun explicitOkHttpCancellationIsExpectedInterruption() {
        assertTrue(IOException("Canceled").isExpectedAudioCdnInterruption())
    }

    @Test
    fun urlFingerprintIsStableShortAndOpaque() {
        val first = "https://rr.example.googlevideo.com/videoplayback?expire=1&sig=secret-a"
        val second = "https://rr.example.googlevideo.com/videoplayback?expire=2&sig=secret-b"

        val firstFingerprint = audioCdnUrlFingerprint(first)

        assertEquals(12, firstFingerprint.length)
        assertEquals(firstFingerprint, audioCdnUrlFingerprint(first))
        assertNotEquals(firstFingerprint, audioCdnUrlFingerprint(second))
        assertFalse(firstFingerprint.contains("secret", ignoreCase = true))
        assertFalse(firstFingerprint.contains("sig", ignoreCase = true))
    }
}
