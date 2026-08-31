package com.nikhil.yt.innertube

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
}
