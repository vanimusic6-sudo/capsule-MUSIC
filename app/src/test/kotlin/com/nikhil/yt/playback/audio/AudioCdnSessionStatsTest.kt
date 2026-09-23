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

    @Test fun `a body that stops short of the declared length is counted as abandoned`() {
        // An undrained body cannot be followed by another request on that socket, so each of
        // these is one more connection the next request has to open cold. In the capture of
        // 21 September, 0 of 20 such closes were followed by a reused socket.
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordClose(bytesDelivered = 1_257, declaredLength = 1_048_576)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("abandonedBodies=1"))
        assertTrue(summary, summary.contains("abandonedPct=100%"))
    }

    @Test fun `a body that arrived whole is not abandoned`() {
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordClose(bytesDelivered = 1_048_576, declaredLength = 1_048_576)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("abandonedBodies=0"))
    }

    @Test fun `a length the server never declared is not judged either way`() {
        // A refused open resolves no length. Counting it as abandoned would double-count the
        // refusal it already appears in and make the two fields say the same thing twice.
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordClose(bytesDelivered = 0, declaredLength = -1)

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("abandonedBodies=0"))
    }

    @Test fun `the protocol mix is reported, because it decides how many sockets there are`() {
        // Under HTTP/1.1 one request holds a connection at a time, so a track's slices open a new
        // socket every other request -- and a socket's first request is the only place a refusal
        // has ever landed. Two captures report http/1.1 for all 240 requests.
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordProtocol("h2")
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordProtocol("http/1.1")

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("http2=1"))
        assertTrue(summary, summary.contains("http2Pct=50%"))
    }

    @Test fun `an unknown or absent protocol is not counted as h2`() {
        AudioCdnSessionStats.recordOpen()
        AudioCdnSessionStats.recordResponse(206, requestIndexOnConnection = 1)
        AudioCdnSessionStats.recordProtocol(null)
        AudioCdnSessionStats.recordProtocol("unknown")

        val summary = requireNotNull(AudioCdnSessionStats.summary())

        assertTrue(summary, summary.contains("http2=0"))
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
