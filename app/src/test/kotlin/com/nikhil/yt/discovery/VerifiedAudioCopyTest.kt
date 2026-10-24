package com.nikhil.yt.discovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VerifiedAudioCopyTest {
    @Test fun completeRecordingCopiesExactlyOnce() {
        val bytes = ByteArray(100_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(100_000L, VerifiedAudioCopy.copy(ByteArrayInputStream(bytes), out, 100_000, 200_000) {})
        assertArrayEquals(bytes, out.toByteArray())
    }
    @Test fun truncatedAndEmptyRecordingsAreNotSuccessful() {
        for ((actual, expected) in listOf(50 to 100L, 0 to -1L, 100 to 50L)) {
            assertThrows(IOException::class.java) {
                VerifiedAudioCopy.copy(ByteArrayInputStream(ByteArray(actual)), ByteArrayOutputStream(), expected, 1000) {}
            }
        }
    }
    @Test fun unknownLengthIsAcceptedOnlyWithinTheLimit() {
        assertEquals(50L, VerifiedAudioCopy.copy(ByteArrayInputStream(ByteArray(50)), ByteArrayOutputStream(), -1, 100) {})
        assertThrows(IOException::class.java) {
            VerifiedAudioCopy.copy(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), -1, 100) {}
        }
    }
    @Test fun diskWriteFailuresPropagateInsteadOfReturningSuccess() {
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("No space left") }
        }
        assertThrows(IOException::class.java) {
            VerifiedAudioCopy.copy(ByteArrayInputStream(ByteArray(100)), output, 100, 100) {}
        }
    }
    @Test fun aSourceThatReturnsNoDataCannotSpinForever() {
        val input = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        assertThrows(IOException::class.java) {
            VerifiedAudioCopy.copy(input, ByteArrayOutputStream(), -1, 100) {}
        }
    }
    @Test fun cancellationPropagatesBeforeWritingAnotherBuffer() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            VerifiedAudioCopy.copy(ByteArrayInputStream(ByteArray(100)), output, 100, 100) {
                throw CancellationException("cancelled")
            }
        }
        assertEquals(0, output.size())
    }
}
