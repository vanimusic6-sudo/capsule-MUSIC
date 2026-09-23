package com.nikhil.yt.playback.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEAD = "rr4---sn-aj4g55-5o.googlevideo.com"
private const val DEAD_REPLICA = "rr1---sn-aj4g55-5o.googlevideo.com"
private const val GOOD = "rr1---sn-ajixh5-55.googlevideo.com"

private class TestClock(var instant: Long = 0L) {
    fun advance(millis: Long) {
        instant += millis
    }
}

private fun healthWith(clock: TestClock) = AudioCdnHostHealth(now = { clock.instant })

class AudioCdnHostHealthTest {
    @Test
    fun `one no-byte open timeout shortens only the same CDN group temporarily`() {
        val clock = TestClock()
        val health = healthWith(clock)

        health.recordUnresponsiveOpen(DEAD)
        assertTrue(health.isUnresponsiveHost(DEAD))
        assertTrue("same edge group has multiple rr replicas", health.isUnresponsiveHost(DEAD_REPLICA))
        assertFalse(health.isUnresponsiveHost(GOOD))
        assertFalse(health.isUnresponsiveHost("example.invalid"))

        clock.advance(CDN_HOST_SUSPECT_MS)
        assertFalse("suspect budget expires", health.isUnresponsiveHost(DEAD))
    }

    @Test
    fun `successful body bytes restore the normal timeout for the served group`() {
        val health = healthWith(TestClock())
        health.recordUnresponsiveOpen(DEAD)
        health.recordUnresponsiveOpen(GOOD)
        health.recordSuccess(DEAD_REPLICA)
        assertFalse(health.isUnresponsiveHost(DEAD))
        assertTrue("unrelated failing CDN remains suspect", health.isUnresponsiveHost(GOOD))
    }

    @Test
    fun `a route change forgets unresponsive groups`() {
        val health = healthWith(TestClock())
        health.recordUnresponsiveOpen(DEAD)
        health.forget()
        assertFalse(health.isUnresponsiveHost(DEAD))
    }

    @Test
    fun `second client on same cold group gets a real probe after first local skip`() {
        val clock = TestClock()
        val health = healthWith(clock)
        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        assertFalse("initial cold-group probe", health.shouldSkipHost(DEAD, "previous"))
        assertTrue("first client is skipped locally", health.shouldSkipHost(DEAD, "song"))
        assertFalse(
            "the same song must not exhaust both clients without a network request",
            health.shouldSkipHost(DEAD_REPLICA, "song"),
        )
        assertFalse(
            "further opens of this song cannot be refused solely by this local cooldown",
            health.shouldSkipHost(DEAD, "song"),
        )
        assertTrue("another song retains cold-group protection", health.shouldSkipHost(DEAD, "other"))
    }

    @Test
    fun `a fresh host is asked`() {
        assertFalse(healthWith(TestClock()).shouldSkipHost(DEAD))
    }

    @Test
    fun `one refusal is not enough to give up on a host`() {
        val health = healthWith(TestClock())

        health.recordFailure(DEAD)

        assertFalse("sn-ajixh5-55 served 25 of 25 the hour after it refused once", health.shouldSkipHost(DEAD))
    }

    @Test
    fun `two refusals are still not enough`() {
        val health = healthWith(TestClock())

        repeat(2) { health.recordFailure(DEAD) }

        assertFalse(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `three refusals in a row leave the group alone`() {
        val clock = TestClock()
        val health = healthWith(clock)

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }

        assertFalse("the first ask after going cold is the probe", health.shouldSkipHost(DEAD))
        assertTrue(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `a served request in between clears the count`() {
        val health = healthWith(TestClock())

        health.recordFailure(DEAD)
        health.recordFailure(DEAD)
        health.recordSuccess(DEAD)
        health.recordFailure(DEAD)
        health.recordFailure(DEAD)

        assertFalse(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `the whole group is left alone, not one replica of it`() {
        val clock = TestClock()
        val health = healthWith(clock)

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)

        assertTrue("a signed link is issued against the group", health.shouldSkipHost(DEAD_REPLICA))
    }

    @Test
    fun `a group that is refusing everything does not take a working one down with it`() {
        val health = healthWith(TestClock())

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }

        assertFalse(health.shouldSkipHost(GOOD))
    }

    @Test
    fun `one request a minute is let through to see whether the group came back`() {
        val clock = TestClock()
        val health = healthWith(clock)

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)
        assertTrue(health.shouldSkipHost(DEAD))

        clock.advance(CDN_HOST_PROBE_INTERVAL_MS)

        assertFalse(health.shouldSkipHost(DEAD))
        assertTrue("and only one", health.shouldSkipHost(DEAD))
    }

    @Test
    fun `a probe that succeeds puts the group straight back into use`() {
        val clock = TestClock()
        val health = healthWith(clock)

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)
        clock.advance(CDN_HOST_PROBE_INTERVAL_MS)
        health.shouldSkipHost(DEAD)
        health.recordSuccess(DEAD)

        assertFalse(health.shouldSkipHost(DEAD))
        assertFalse(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `the group is asked again once the cold spell is over`() {
        val clock = TestClock()
        val health = healthWith(clock)

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)
        assertTrue(health.shouldSkipHost(DEAD))

        clock.advance(CDN_HOST_COLD_MS)

        assertFalse(health.shouldSkipHost(DEAD))
        assertFalse("and the count starts over, not one strike from cold", health.shouldSkipHost(DEAD))
    }

    @Test
    fun `nothing learned on one network is carried to the next`() {
        val health = healthWith(TestClock())

        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.forget()

        assertFalse(health.shouldSkipHost(DEAD))
        assertFalse(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `refusing stops once it has stopped leading anywhere`() {
        // A capture showed a re-resolve landing on the same cold group twenty times running. Every
        // open was refused here, the player retried, and the song never started.
        val clock = TestClock()
        val health = healthWith(clock)
        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)

        repeat(CDN_HOST_MAX_CONSECUTIVE_SKIPS) {
            assertTrue("skip $it should still be refused", health.shouldSkipHost(DEAD))
        }

        assertFalse(
            "silence is worse than a refusal that might have been served",
            health.shouldSkipHost(DEAD),
        )
        assertFalse("and it stays open, not every other time", health.shouldSkipHost(DEAD))
    }

    @Test
    fun `anything served anywhere puts the refusals back`() {
        val clock = TestClock()
        val health = healthWith(clock)
        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)
        repeat(CDN_HOST_MAX_CONSECUTIVE_SKIPS + 1) { health.shouldSkipHost(DEAD) }

        // Served by some other group, which is exactly the case the memory is for.
        health.recordSuccess(GOOD)

        assertTrue(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `a new route clears the budget too`() {
        val health = healthWith(TestClock())
        repeat(CDN_HOST_FAILURES_BEFORE_COLD) { health.recordFailure(DEAD) }
        health.shouldSkipHost(DEAD)
        repeat(CDN_HOST_MAX_CONSECUTIVE_SKIPS + 1) { health.shouldSkipHost(DEAD) }

        health.forget()

        assertFalse(health.shouldSkipHost(DEAD))
    }

    @Test
    fun `a host with no group in its name is never held against anything`() {
        val health = healthWith(TestClock())

        repeat(10) { health.recordFailure("cdn.example.com") }

        assertFalse(health.shouldSkipHost("cdn.example.com"))
        assertFalse(health.shouldSkipHost(null))
    }
}
