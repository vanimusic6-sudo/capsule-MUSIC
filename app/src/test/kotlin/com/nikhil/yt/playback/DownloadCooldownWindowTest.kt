package com.nikhil.yt.playback

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCooldownWindowTest {
    @Test
    fun fullTenMinuteRateLimitWaitsWithoutPrematureDownloadFailure() = runBlocking {
        var now = 1_000L
        var sleeps = 0
        awaitDownloadCooldownWindow(
            nowMs = { now },
            globalRemainingMs = { current -> (601_000L - current).coerceAtLeast(0L) },
            downloadRemainingMs = { 0L },
            localCooldownUntilMs = { 0L },
            sleep = { interval ->
                assertTrue(interval in 1L..2_000L)
                now += interval
                sleeps++
            },
        )
        assertEquals(601_000L, now)
        assertEquals(300, sleeps)
    }

    @Test
    fun allThreeIndependentCooldownsMustClear() = runBlocking {
        var now = 0L
        awaitDownloadCooldownWindow(
            nowMs = { now },
            globalRemainingMs = { t -> (2_000L - t).coerceAtLeast(0L) },
            downloadRemainingMs = { (4_000L - now).coerceAtLeast(0L) },
            localCooldownUntilMs = { 3_000L },
            sleep = { now += it },
        )
        assertEquals(4_000L, now)
    }

    @Test
    fun cancellingUserDownloadInterruptsTheWaitImmediately() = runBlocking {
        var calls = 0
        try {
            awaitDownloadCooldownWindow(
                nowMs = { 0L },
                globalRemainingMs = { 600_000L },
                downloadRemainingMs = { 0L },
                localCooldownUntilMs = { 0L },
                sleep = {
                    calls++
                    throw CancellationException("Download removed by user")
                },
            )
            throw AssertionError("Expected cancellation to propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("Download removed by user", cancelled.message)
            assertEquals(1, calls)
        }
    }
}
