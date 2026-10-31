package com.nikhil.yt.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResolveFailureClassificationTest {
    private class PlayerRequestTimeoutException : IOException("one /player client timed out")

    private fun httpStatus(code: Int): InvalidResponseCodeException =
        InvalidResponseCodeException(
            code, "http status", null, emptyMap(),
            DataSpec.Builder().setUri(Uri.parse("https://example.test/")).build(),
            ByteArray(0),
        )

    @Test fun timeoutRetriesAreBoundedByCallerNotMistakenForBotCheck() {
        assertTrue(shouldRetryDownloadResolve(PlayerRequestTimeoutException()))
        assertTrue(shouldRetryDownloadResolve(IOException("wrapped", SocketTimeoutException("timed out"))))
        assertFalse(isLikelyDownloadThrottleSignal(PlayerRequestTimeoutException()))
        assertFalse(isLikelyDownloadThrottleSignal(SocketTimeoutException("timed out")))
        assertFalse(isLikelyDownloadThrottleSignal(SocketException("Connection reset")))
    }

    @Test fun httpRefusalsAndCancellationNeverReplayAsTransportTimeout() {
        listOf(403, 410, 429, 500).forEach { code ->
            assertFalse(shouldRetryDownloadResolve(httpStatus(code)))
        }
        assertFalse(shouldRetryDownloadResolve(IOException("cancelled", CancellationException("user tapped stop"))))
        assertFalse(shouldRetryDownloadResolve(IOException("no playable clients")))
        assertFalse(shouldRetryDownloadResolve(IOException("unsupported client")))
    }

    @Test fun onlyExplicitRateLimitsTriggerDownloadCooldownHeuristic() {
        assertTrue(isLikelyDownloadThrottleSignal(IOException("HTTP 429 Too Many Requests")))
        assertTrue(isLikelyDownloadThrottleSignal(IOException("rate limit reached")))
        assertTrue(isLikelyDownloadThrottleSignal(IOException("quota exceeded")))
        assertFalse(isLikelyDownloadThrottleSignal(IOException("temporarily unavailable")))
        assertFalse(isLikelyDownloadThrottleSignal(IOException("no playable clients")))
        assertFalse(isLikelyDownloadThrottleSignal(IOException("Read timed out")))
        assertFalse(isLikelyDownloadThrottleSignal(IOException("connection reset by peer")))
    }
}
