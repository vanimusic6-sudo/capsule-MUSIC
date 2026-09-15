package com.nikhil.yt.playback.audio

import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioResolveAwaiterTest {
    @Test fun cancelledSharedPrefetchIsRejoinedForTheStillSelectedSong() = runTest {
        var calls = 0
        val value = awaitForegroundAudioResolve(isRelevant = { true }) {
            if (++calls == 1) Result.failure(CancellationException("Policy changed"))
            else Result.success(7)
        }
        assertEquals(7, value)
        assertEquals(2, calls)
    }

    @Test fun abandonedTrackDoesNotRestartOrBecomeAPlaybackException() = runTest {
        val cancellation = CancellationException("Track is no longer near playback")
        var calls = 0
        try {
            awaitForegroundAudioResolve<Int>(isRelevant = { false }) {
                calls++
                throw cancellation
            }
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
            val loaderFailure = cancelledAudioLoad(caught)
            assertTrue(loaderFailure is InterruptedIOException)
            assertSame(cancellation, loaderFailure.cause)
        }
        assertEquals(1, calls)
    }

    @Test fun outerTimeoutIsNeverRetried() = runTest {
        var calls = 0
        try {
            withTimeout(100) {
                awaitForegroundAudioResolve<Int>(isRelevant = { true }) {
                    calls++
                    awaitCancellation()
                }
            }
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test fun cancelledLoaderNeverStartsAnotherResolve() = runTest {
        var calls = 0
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val task = async {
            awaitForegroundAudioResolve<Int>(isRelevant = { true }) {
                calls++
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        task.cancel()
        task.join()
        assertEquals(1, calls)
        assertTrue(task.isCancelled)
    }
}
