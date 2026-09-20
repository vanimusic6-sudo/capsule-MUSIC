package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger's only job is to answer "was this the first request on this socket, and how old is
 * it" — the one fact the captures could not supply while refusals were being blamed on the
 * request rather than on the connection carrying it.
 */
class AudioCdnConnectionLedgerTest {
    @Test fun `a connection's first request is reported as its first`() {
        val ledger = AudioCdnConnectionLedger()

        assertEquals(1, ledger.record(connectionId = 7, nowMs = 1_000L).requestIndex)
        assertEquals(2, ledger.record(connectionId = 7, nowMs = 1_500L).requestIndex)
        assertEquals(3, ledger.record(connectionId = 7, nowMs = 9_000L).requestIndex)
    }

    @Test fun `age is measured from the connection's first sighting, not the last`() {
        val ledger = AudioCdnConnectionLedger()
        ledger.record(connectionId = 7, nowMs = 1_000L)
        ledger.record(connectionId = 7, nowMs = 5_000L)

        assertEquals(8_000L, ledger.record(connectionId = 7, nowMs = 9_000L).ageMs)
    }

    @Test fun `connections are counted apart`() {
        val ledger = AudioCdnConnectionLedger()
        ledger.record(connectionId = 7, nowMs = 1_000L)
        ledger.record(connectionId = 7, nowMs = 1_100L)

        val other = ledger.record(connectionId = 8, nowMs = 1_200L)

        assertEquals(1, other.requestIndex)
        assertEquals(0L, other.ageMs)
    }

    @Test fun `a request with no connection is not a new connection`() {
        // OkHttp can hand an interceptor no connection at all. Counting that as a socket's first
        // request would invent exactly the event this ledger exists to count.
        val use = AudioCdnConnectionLedger().record(connectionId = -1, nowMs = 1_000L)

        assertEquals(0, use.requestIndex)
        assertFalse(isCdnWireWorthReporting(statusCode = 200, requestIndexOnConnection = 0))
    }

    @Test fun `a clock that goes backwards does not produce a negative age`() {
        val ledger = AudioCdnConnectionLedger()
        ledger.record(connectionId = 7, nowMs = 9_000L)

        assertEquals(0L, ledger.record(connectionId = 7, nowMs = 1_000L).ageMs)
    }

    @Test fun `the ledger cannot grow without bound`() {
        val ledger = AudioCdnConnectionLedger(capacity = 4)
        for (id in 1..64) {
            ledger.record(connectionId = id, nowMs = id.toLong())
        }

        // The oldest connections have been dropped, so one of them looks new again. That is the
        // accepted cost of a bounded map, and it can only understate a connection's history.
        assertEquals(1, ledger.record(connectionId = 1, nowMs = 100L).requestIndex)
        // The most recent ones are still remembered.
        assertEquals(2, ledger.record(connectionId = 64, nowMs = 100L).requestIndex)
    }

    @Test fun `a route change forgets every connection`() {
        val ledger = AudioCdnConnectionLedger()
        ledger.record(connectionId = 7, nowMs = 1_000L)

        ledger.forget()

        assertEquals(1, ledger.record(connectionId = 7, nowMs = 2_000L).requestIndex)
    }

    @Test fun `the first request on a connection is reported even when it succeeds`() {
        // This is the whole point: a refusal already gets a line, and without the successful
        // first requests to compare it against the line proves nothing.
        assertTrue(isCdnWireWorthReporting(statusCode = 200, requestIndexOnConnection = 1))
        assertTrue(isCdnWireWorthReporting(statusCode = 403, requestIndexOnConnection = 1))
        assertTrue(isCdnWireWorthReporting(statusCode = 403, requestIndexOnConnection = 9))
        assertFalse(isCdnWireWorthReporting(statusCode = 206, requestIndexOnConnection = 2))
    }
}

/**
 * Whether a response leaves its socket fit for another request, which is what decides how many
 * "first request on a new connection" events a session has — and every refusal in the capture
 * that prompted this landed on exactly such a request.
 */
class CdnConnectionReuseTest {
    @Test fun `a response that declares its length can be followed by another`() {
        assertTrue(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = null,
                contentLength = 1_048_576L,
                transferEncoding = null,
            ),
        )
    }

    @Test fun `an empty redirect that declares zero length is still reusable`() {
        // The interesting case: a 302 with Content-Length: 0 keeps the socket, one without it
        // does not, and that difference is worth a whole TLS handshake per redirect.
        assertTrue(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = null,
                contentLength = 0L,
                transferEncoding = null,
            ),
        )
    }

    @Test fun `a response with no length and no chunking ends by closing`() {
        assertFalse(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = null,
                contentLength = -1L,
                transferEncoding = null,
            ),
        )
    }

    @Test fun `chunked framing delimits the body without a length`() {
        assertTrue(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = null,
                contentLength = -1L,
                transferEncoding = "chunked",
            ),
        )
    }

    @Test fun `an explicit close wins over any framing`() {
        assertFalse(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = "close",
                contentLength = 1_048_576L,
                transferEncoding = "chunked",
            ),
        )
        // Header values are case-insensitive and arrive combined with other tokens.
        assertFalse(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = "Keep-Alive, Close",
                contentLength = 0L,
                transferEncoding = null,
            ),
        )
    }

    @Test fun `keep-alive is not mistaken for close`() {
        assertTrue(
            cdnResponseKeepsConnectionUsable(
                connectionHeader = "keep-alive",
                contentLength = 512L,
                transferEncoding = null,
            ),
        )
    }
}
