package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Telling the two address families apart, and keeping neither address.
 *
 * A googlevideo link is issued to the address that asked for it and carries that address in its own
 * query string. If the media request leaves by a different one — routine on a dual-stack mobile
 * network, where one connection gets an A record and the next an AAAA — the CDN refuses the link
 * with a bare 403 and an empty body, which is what the device has been receiving. Comparing the two
 * families is enough to see that happen; the addresses themselves are never kept.
 */
class AddressFamilyTest {
    @Test
    fun anIpv4LiteralIsRecognised() {
        assertEquals("v4", addressFamilyOf("203.0.113.7"))
    }

    @Test
    fun anIpv6LiteralIsRecognised() {
        assertEquals("v6", addressFamilyOf("2001:db8::1"))
        assertEquals("v6", addressFamilyOf("::1"))
    }

    @Test
    fun aMissingAddressIsNotMistakenForEither() {
        assertEquals("none", addressFamilyOf(null))
        assertEquals("none", addressFamilyOf(""))
        assertEquals("none", addressFamilyOf("   "))
    }

    /** A malformed value must not be reported as a family, or a mismatch would look like a match. */
    @Test
    fun anythingElseIsNamedAsSuch() {
        assertEquals("other", addressFamilyOf("203.0.113"))
        assertEquals("other", addressFamilyOf("not-an-address"))
    }
}
