package com.nikhil.yt.innertube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YouTubeRequestGuardTest {
    @Test fun concurrentMetadataStartsArePaced() = runTest {
        val guard = YouTubeRequestGuard(nowMs = { currentTime })
        val starts = mutableListOf<Long>()
        (1..4).map { async { guard.execute { starts += currentTime } } }.awaitAll()
        assertEquals(listOf(0L, 250L, 500L, 750L), starts)
    }

    @Test fun queuedRequestsStopAfter429AndLocalRejectionsDoNotExtendCooldown() = runTest {
        val guard = YouTubeRequestGuard(cooldownMs = 1_000, nowMs = { currentTime })
        guard.execute { }
        val pending = async {
            runCatching { guard.execute { fail("Queued HTTP request escaped the cooldown") } }
        }
        runCurrent()
        guard.observeFailure(IllegalStateException("HTTP 429"))
        advanceTimeBy(250)
        runCurrent()
        val rejection = pending.await().exceptionOrNull()!!
        assertTrue(rejection is YouTubeRequestBlockedException)
        guard.observeFailure(rejection)
        advanceTimeBy(750)
        assertNull(guard.blockedExceptionOrNull())
    }

    @Test fun permissionAndAgeFailuresDoNotDisablePlayback() {
        val guard = YouTubeRequestGuard()
        guard.observeFailure(IllegalStateException("Sign in to confirm your age"))
        guard.observeFailure(IllegalStateException("HTTP 403"))
        assertNull(guard.blockedExceptionOrNull())
    }
}
