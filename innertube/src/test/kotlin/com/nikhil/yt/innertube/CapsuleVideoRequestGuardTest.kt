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

    @Test
    fun expiredOrUnsupportedCdnLinksDoNotBanOtherClips() = kotlinx.coroutines.runBlocking {
        repeat(20) {
            assertEquals(CapsuleVideoRequestGuard.FailureKind.FORBIDDEN,
                CapsuleVideoRequestGuard.noteStreamStatus(403))
        }
        assertFalse(CapsuleVideoRequestGuard.isBlocked())
        CapsuleVideoRequestGuard.beforeStreamProbe()
        assertFalse(CapsuleVideoRequestGuard.noteHttpStatus(403))
        assertEquals(CapsuleVideoRequestGuard.FailureKind.FORBIDDEN,
            CapsuleVideoRequestGuard.noteApiFailure(IllegalStateException("HTTP 403")))
        assertFalse(CapsuleVideoRequestGuard.isBlocked())
        CapsuleVideoRequestGuard.noteStreamStatus(429)
        assertTrue(CapsuleVideoRequestGuard.isBlocked())
    }

    @Test
    fun old403OnlyCooldownIsMigratedWithoutRemovingRealBotOrRateLimitCooldowns() {
        val now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        for (reason in listOf("repeated stream 403", "http 403", "forbidden")) {
            CapsuleVideoRequestGuard.restore(CapsuleVideoRequestGuard.Snapshot(now + 21_600_000L, 3, now, reason))
            assertFalse(CapsuleVideoRequestGuard.isBlocked())
            assertEquals(0, CapsuleVideoRequestGuard.snapshot().escalationLevel)
        }
        for (reason in listOf("http 429 (stream)", "rate_limited", "bot_blocked", "bot check")) {
            CapsuleVideoRequestGuard.restore(CapsuleVideoRequestGuard.Snapshot(now + 600_000L, 1, now, reason))
            assertTrue(CapsuleVideoRequestGuard.isBlocked())
            assertEquals(reason, CapsuleVideoRequestGuard.blockReason())
        }
    }

    @Test fun simultaneousSegmentFailuresAreOneCooldownIncident() {
        var now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        CapsuleVideoRequestGuard.noteStreamStatus(429)
        val initial = CapsuleVideoRequestGuard.snapshot()
        repeat(20) { CapsuleVideoRequestGuard.noteStreamStatus(429) }
        assertEquals(initial, CapsuleVideoRequestGuard.snapshot())
        assertEquals(600_000L, CapsuleVideoRequestGuard.remainingBackoffMs())
        now += 600_001L
        CapsuleVideoRequestGuard.noteStreamStatus(429)
        assertEquals(1_800_000L, CapsuleVideoRequestGuard.remainingBackoffMs())
    }

    @Test fun presentedVideoResetsEscalationWithoutOverridingAnActiveBotCooldown() {
        var now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("bot_blocked")
        assertEquals(1, CapsuleVideoRequestGuard.snapshot().escalationLevel)
        CapsuleVideoRequestGuard.noteVideoRenderedSuccessfully()
        assertEquals(1, CapsuleVideoRequestGuard.snapshot().escalationLevel)
        assertTrue(CapsuleVideoRequestGuard.isBlocked())

        now += 600_001L
        CapsuleVideoRequestGuard.noteVideoRenderedSuccessfully()
        assertEquals(0, CapsuleVideoRequestGuard.snapshot().escalationLevel)
        assertEquals(0L, CapsuleVideoRequestGuard.remainingBackoffMs())
        CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("bot_blocked")
        // After a successful clip, a new, independently confirmed incident
        // starts at 10 minutes again, not the previous 30m/2h escalation.
        assertEquals(600_000L, CapsuleVideoRequestGuard.remainingBackoffMs())
    }

    @Test fun developerResetPersistsAndOldReplyCannotRestoreTheTimeout() {
        val now = 100_000L
        CapsuleVideoRequestGuard.resetForTests { now }
        val oldEpoch = CapsuleVideoRequestGuard.resetEpoch()
        CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("bot_blocked", oldEpoch)
        var saved: CapsuleVideoRequestGuard.Snapshot? = null
        CapsuleVideoRequestGuard.onStateChanged = { saved = it }
        try {
            CapsuleVideoRequestGuard.resetCooldown()
            assertEquals(CapsuleVideoRequestGuard.Snapshot(0L, 0, 0L, null), saved)
            CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("bot_blocked", oldEpoch)
            CapsuleVideoRequestGuard.noteStreamStatus(429, oldEpoch)
            CapsuleVideoRequestGuard.noteApiFailure(IllegalStateException("Confirm you are not a bot"), oldEpoch)
            assertFalse(CapsuleVideoRequestGuard.isBlocked())
            CapsuleVideoRequestGuard.noteBlockedAfterAllAttempts("bot_blocked")
            assertEquals(600_000L, CapsuleVideoRequestGuard.remainingBackoffMs())
        } finally { CapsuleVideoRequestGuard.onStateChanged = null }
    }

}
