package com.nikhil.yt.playback.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A refusal rate you can compare between sessions.
 *
 * Every rate in this investigation was counted by hand out of a log. Three refusals in twelve
 * opens reads exactly like three in a hundred while meaning something completely different, and
 * twice a change that helped could not be credited because before and after were eyeballed from
 * captures of different lengths.
 */
class AudioCdnSessionStatsTest {
    @Before fun reset() = AudioCdnSessionStats.forget()

    @After fun tidy() = AudioCdnSessionStats.forget()

    @Test fun `a session that has done nothing has no summary`() {
        assertNull(AudioCdnSessionStats.summary())
    }

    @Test fun `opens and refusals are counted and turned into a rate`() {
        repeat(100) { AudioCdnSessionStats.recordOpen() }
        repeat(3) { AudioCdnSessionStats.recordResponse(403, requestIndexOnConnection = 1) }
        repeat(97) { AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 2) }

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("opens=100"))
        assertTrue(summary, summary.contains("refused=3"))
        assertTrue(summary, summary.contains("refusedPct=3%"))
    }

    @Test fun `refusals on a connection's first request are counted apart`() {
        // The single strongest signal these captures have produced.
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(403, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordResponse(403, requestIndexOnConnection = 4)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("refused=2"))
        assertTrue(summary, summary.contains("refusedOnFirstRequest=1"))
    }

    @Test fun `redirects are counted as redirects, not as refusals`() {
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(302, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("redirects=1"))
        assertTrue(summary, summary.contains("refused=0"))
        assertTrue(summary, summary.contains("redirectPct=50%"))
    }

    @Test fun `socket depth is reported, because redirects are what shortens it`() {
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 2)
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 3)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("sockets=1"))
        assertTrue(summary, summary.contains("requestsPerSocket=3.0"))
    }

    @Test fun `a route change starts the count again`() {
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(403, requestIndexOnConnection = 1)

        AudioCdnSessionStats.forget()

        assertNull(AudioCdnSessionStats.summary())
    }

    @Test fun `no division by zero when a session has requests but no opens`() {
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("refusedPct=n/a"))
    }

    @Test fun `the rate helpers round the way a reader expects`() {
        assertEquals("3%", AudioCdnSessionStats.percent(3, 100))
        assertEquals("0%", AudioCdnSessionStats.percent(0, 40))
        assertEquals("n/a", AudioCdnSessionStats.percent(1, 0))
        assertEquals("1.8", AudioCdnSessionStats.ratio(9, 5))
        assertEquals("n/a", AudioCdnSessionStats.ratio(9, 0))
    }
}
