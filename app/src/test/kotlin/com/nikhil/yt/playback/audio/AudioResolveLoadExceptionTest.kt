package com.nikhil.yt.playback.audio

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class AudioResolveLoadExceptionTest {
    @Test fun extractorFailureUsesIoBoundaryAndPreservesOriginalCause() {
        val cause = IllegalStateException("No safe AUDIO client profile is currently available")
        val result = audioResolveLoadFailure(cause)
        assertTrue(result is IOException)
        assertTrue(result is AudioResolveLoadException)
        assertSame(cause, result.cause)
    }

    @Test fun networkTimeoutIsPreservedForMedia3RetryClassification() {
        val cause = SocketTimeoutException("request timeout")
        assertSame(cause, audioResolveLoadFailure(cause))
        assertFalse(audioResolveLoadFailure(cause) is AudioResolveLoadException)
    }

    @Test fun cancellationRemainsAnInterruptionRatherThanUnavailableTrack() {
        val cause = CancellationException("obsolete selection")
        val result = audioResolveLoadFailure(cause)
        assertTrue(result is InterruptedIOException)
        assertSame(cause, result.cause)
    }

    @Test fun explicitSafetyBlockIsRetainedInsteadOfBecomingUnexpectedLoaderException() {
        val cause = PlaybackException("request paused", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        val result = audioResolveLoadFailure(cause)
        assertTrue(result is IOException)
        assertSame(cause, result.cause)
    }
}
