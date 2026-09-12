package com.nikhil.yt.playback.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPrewarmTest {
    @Test
    fun startupAndFirstResolveShareOnePreparationAndWaitForCompletion() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope) {
            initializations++
            delay(1_000)
        }
        val startup = warmup.start()
        runCurrent()
        advanceTimeBy(135)
        val playback = async { warmup.start().await().getOrThrow() }
        runCurrent()
        assertFalse(playback.isCompleted)
        playback.await()
        assertEquals(1_000L, currentTime)
        assertSame(startup, warmup.start())
        assertEquals(1, initializations)
    }

    @Test
    fun skippingTheWaitingTrackKeepsWarmupForTheNextTrackAndClient() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope) {
            initializations++
            delay(1_000)
        }
        val firstTrack = async { warmup.start().await() }
        runCurrent()
        advanceTimeBy(200)
        firstTrack.cancelAndJoin()
        val secondTrack = warmup.start()
        assertFalse(secondTrack.isCancelled)
        secondTrack.await().getOrThrow()
        assertEquals(1, initializations)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun timeoutIsBoundedAndNextRequestCanRetryWarmup() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope, timeoutMs = 800) {
            initializations++
            awaitCancellation()
        }
        val first = warmup.start()
        assertTrue(first.await().exceptionOrNull() is SocketTimeoutException)
        assertEquals(800L, currentTime)

        val second = warmup.start()
        assertFalse(first === second)
        assertTrue(second.await().exceptionOrNull() is SocketTimeoutException)
        assertEquals(1_600L, currentTime)
        assertEquals(2, initializations)
    }

    @Test
    fun ordinaryFailureIsNotCachedAfterItCompletes() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope) {
            initializations++
            if (initializations == 1) error("first attempt failed")
        }

        assertTrue(warmup.start().await().isFailure)
        assertTrue(warmup.start().await().isSuccess)
        assertEquals(2, initializations)
    }

    @Test
    fun retiringTheExtractionSessionWaitsForWarmupCleanup() = runTest {
        var cleanedUp = false
        val warmup = SharedPrewarm(backgroundScope) {
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        }
        warmup.start()
        runCurrent()
        warmup.cancelAndJoin()
        assertTrue(cleanedUp)
        assertTrue(warmup.start().isCancelled)
    }
}
