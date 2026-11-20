package com.nikhil.yt.playback.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class CapsuleVideoFastStartPolicyTest {
    @After fun resetRouteHealth() = CapsuleVideoFastStartPolicy.clearForTest()

    @Test fun slowWebRouteIsTemporarilyBoundedWithoutStartingExtraClients() {
        val start = 1_000_000L
        val originalBudget = CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start)
        assertEquals(5_200L, originalBudget)
        CapsuleVideoFastStartPolicy.notePrimaryResult(
            success = false, elapsedMs = 5_200L, allowedMs = originalBudget,
            nowMs = start,
        )
        assertEquals(2_800L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start + 500L))
        assertEquals(5_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start + 301_000L))
        CapsuleVideoFastStartPolicy.notePrimaryResult(
            success = false, elapsedMs = 20L, allowedMs = originalBudget, nowMs = start,
        )
        assertEquals(5_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start + 301_000L))
        CapsuleVideoFastStartPolicy.notePrimaryResult(
            success = false, elapsedMs = 4_800L, allowedMs = originalBudget, nowMs = start + 302_000L,
        )
        assertEquals(2_800L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start + 302_001L))
        CapsuleVideoFastStartPolicy.notePrimaryResult(
            success = true, elapsedMs = 1_500L, allowedMs = 2_800L, nowMs = start + 302_100L,
        )
        assertEquals(5_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720, start + 302_101L))
    }

    @Test fun fastRouteBudgetsNeverWaitAllTenSeconds() {
        assertEquals(4_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(480))
        assertEquals(5_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(720))
        assertEquals(6_200L, CapsuleVideoFastStartPolicy.primaryBudgetMs(1080))
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
