package com.nikhil.yt.playback.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoFastStartPolicyTest {
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
        assertSame(cancelled, thrown)
        assertFalse(fallbackCalled)
    }
}
