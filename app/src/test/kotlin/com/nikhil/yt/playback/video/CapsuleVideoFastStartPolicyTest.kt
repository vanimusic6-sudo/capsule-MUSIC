package com.nikhil.yt.playback.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoFastStartPolicyTest {
    @Test fun fastRouteBudgetsNeverWaitAllTenSeconds() {
        assertEquals(3_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(480))
        assertEquals(3_800L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720))
        assertEquals(4_600L, CapsuleVideoFastStartPolicy.primaryBudgetMs(1080))
        assertTrue(CapsuleVideoFastStartPolicy.primaryBudgetMs(720) < 4_000L)
    }
    @Test fun twoDistinctWebDeadlinesTemporarilyPreferTheFallback() {
        var now = 1_000L
        val health = CapsuleVideoPrimaryRouteHealth({ now }, cooldownMs = 30_000L)
        assertTrue(health.shouldTryWeb("session-a"))
        health.onWebDeadline("first")
        assertTrue("One slow clip cannot blacklist a working WEB client",
            health.shouldTryWeb("session-a"))
        health.onWebDeadline("first")
        assertTrue("A repeat of the same clip is not independent evidence",
            health.shouldTryWeb("session-a"))
        health.onWebDeadline("second")
        assertFalse("Independent timeouts should avoid another 5s wait",
            health.shouldTryWeb("session-a"))
        now += 30_000L
        assertTrue("WEB must be reprobed after bounded cooldown",
            health.shouldTryWeb("session-a"))
    }

    @Test fun healthyWebAndChangedSessionForgetTheOldTimeouts() {
        var now = 1_000L
        val health = CapsuleVideoPrimaryRouteHealth({ now }, cooldownMs = 30_000L)
        health.shouldTryWeb("session-a")
        health.onWebDeadline("one")
        health.onWebCompleted()
        health.onWebDeadline("two")
        assertTrue("One deadline after success is not a slow route",
            health.shouldTryWeb("session-a"))
        health.onWebDeadline("three")
        assertFalse(health.shouldTryWeb("session-a"))
        assertTrue("Network/account session changes must restore WEB immediately",
            health.shouldTryWeb("session-b"))
    }

    @Test fun promptUnavailableResponseDoesNotCountAsTimeout() = runBlocking {
        var deadlines = 0
        val result = capsuleFastPrimaryOrFallback<String>(
            100, primary = { null }, fallback = { "remote" },
            onPrimaryDeadline = { deadlines++ },
        )
        assertEquals("remote", result)
        assertEquals(0, deadlines)
    }

    @Test fun timedOutPrimaryReportsOneDeadlineBeforeFallback() = runBlocking {
        var deadlines = 0
        val result = capsuleFastPrimaryOrFallback(
            20, primary = { delay(200); "web" },
            fallback = { "remote" },
            onPrimaryDeadline = { deadlines++ },
        )
        assertEquals("remote", result)
        assertEquals(1, deadlines)
    }

    @Test fun healthyPrimarySkipsFallback() = runBlocking {
        var fallbackCalls = 0
        val actual = capsuleFastPrimaryOrFallback(200, { "web" }) {
            fallbackCalls++
            "remote"
        }
        assertEquals("web", actual)
        assertEquals(0, fallbackCalls)
    }
    @Test fun stalledPrimaryCancelsBeforeFallback() = runBlocking {
        var cancelled = false
        val actual = capsuleFastPrimaryOrFallback(20, {
            try { delay(300); "web" } finally { cancelled = true }
        }) { "remote" }
        assertEquals("remote", actual)
        assertTrue(cancelled)
    }
    @Test fun cancellationNeverGetsRewrittenAsFallback() = runBlocking {
        val cancelled = CancellationException("new song")
        var fallbackCalled = false
        val thrown = runCatching {
            capsuleFastPrimaryOrFallback<String>(200, { throw cancelled }) {
                fallbackCalled = true
                "remote"
            }
        }.exceptionOrNull()
        assertTrue("Cancellation must propagate without starting a fallback", thrown is CancellationException)
        assertEquals(cancelled.message, thrown?.message)
        assertFalse(fallbackCalled)
    }
}
