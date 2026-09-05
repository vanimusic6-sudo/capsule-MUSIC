package com.nikhil.yt.innertube

import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CapsuleVideoRequestGuardTest {
    @Before
    fun setUp() {
        CapsuleVideoRequestGuard.resetForTests()
    }

    @After
    fun tearDown() {
        CapsuleVideoRequestGuard.resetForTests()
    }

    @Test
    fun ageLoginDoesNotOpenBreaker() {
        val kind =
            CapsuleVideoRequestGuard.noteApiFailure(
                IllegalStateException("Sign in to confirm your age"),
            )

        assertEquals(CapsuleVideoRequestGuard.FailureKind.PERMANENT, kind)
        assertFalse(CapsuleVideoRequestGuard.isBlocked())
    }

    @Test
    fun explicitBotCheckOpensBreaker() {
        val kind =
            CapsuleVideoRequestGuard.noteApiFailure(
                IllegalStateException("Confirm you're not a bot"),
            )

        assertEquals(CapsuleVideoRequestGuard.FailureKind.BOT_CHECK, kind)
        assertTrue(CapsuleVideoRequestGuard.isBlocked())
    }

    @Test
    fun stream429OpensBreakerImmediately() {
        assertEquals(
            CapsuleVideoRequestGuard.FailureKind.RATE_LIMITED,
            CapsuleVideoRequestGuard.noteStreamStatus(429),
        )
        assertTrue(CapsuleVideoRequestGuard.isBlocked())
    }
    @Test
    fun queuedRequestRechecksABreakerOpenedDuringItsDelay() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.supervisorScope {
            CapsuleVideoRequestGuard.beforeMetadataRequest()
            val queued = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                CapsuleVideoRequestGuard.beforeMetadataRequest()
            }
            assertFalse(queued.isCompleted)
            CapsuleVideoRequestGuard.noteApiFailure(IllegalStateException("Confirm you're not a bot"))
            try {
                queued.await()
                org.junit.Assert.fail("Queued request escaped the breaker")
            } catch (_: CapsuleVideoRequestGuard.RequestBlockedException) { }
        }
    }

}
