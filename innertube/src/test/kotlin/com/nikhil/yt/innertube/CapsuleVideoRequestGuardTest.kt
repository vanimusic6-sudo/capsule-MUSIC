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
    fun manyHealthyClipsAndHlsSegmentsCannotExhaustTheNextSearch() = kotlinx.coroutines.runBlocking {
        var now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        repeat(35) {
            // Matching and exact extraction are separate paced metadata calls.
            CapsuleVideoRequestGuard.beforeMetadataRequest()
            now += 2_000L
            CapsuleVideoRequestGuard.beforeMetadataRequest()
            repeat(100) {
                CapsuleVideoRequestGuard.beforeStreamProbe()
                CapsuleVideoRequestGuard.noteStreamStatus(200)
            }
            now += 2_000L
        }
        assertFalse(CapsuleVideoRequestGuard.isBlocked())
        CapsuleVideoRequestGuard.beforeMetadataRequest()
    }

    @Test
    fun ordinaryMediaReadsBypassMetadataReservationsButHonorServerCooldown() = kotlinx.coroutines.runBlocking {
        CapsuleVideoRequestGuard.beforeMetadataRequest()
        val queued = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            CapsuleVideoRequestGuard.beforeMetadataRequest()
        }
        assertFalse(queued.isCompleted)
        repeat(100) { CapsuleVideoRequestGuard.beforeStreamProbe() }
        queued.cancel()
        queued.join()
        CapsuleVideoRequestGuard.noteStreamStatus(429)
        try {
            CapsuleVideoRequestGuard.beforeStreamProbe()
            org.junit.Assert.fail("Media request escaped a server rate limit")
        } catch (_: CapsuleVideoRequestGuard.RequestBlockedException) { }
    }
    @Test
    fun cancelledQueuedMetadataDoesNotKeepAnObsoleteReservation() = kotlinx.coroutines.runBlocking {
        var now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        CapsuleVideoRequestGuard.beforeMetadataRequest()
        val obsolete = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            CapsuleVideoRequestGuard.beforeMetadataRequest()
        }
        assertFalse(obsolete.isCompleted)
        obsolete.cancel(); obsolete.join()
        now += 1_500L
        val next = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            CapsuleVideoRequestGuard.beforeMetadataRequest()
        }
        assertTrue(next.isCompleted)
        next.await()
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
