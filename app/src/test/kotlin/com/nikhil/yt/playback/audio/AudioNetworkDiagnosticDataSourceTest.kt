package com.nikhil.yt.playback.audio

import org.junit.Assert.assertFalse
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
}
