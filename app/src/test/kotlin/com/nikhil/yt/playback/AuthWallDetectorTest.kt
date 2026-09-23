package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthWallDetectorTest {
    @Test
    fun `one song turned away is the song`() {
        val detector = AuthWallDetector()

        assertFalse(detector.recordAuthRequired("03vjkV3I0WU"))
    }

    @Test
    fun `the same song turned away twice is still one song`() {
        val detector = AuthWallDetector()

        detector.recordAuthRequired("03vjkV3I0WU")

        assertFalse("the retry of one song is not a second song", detector.recordAuthRequired("03vjkV3I0WU"))
    }

    @Test
    fun `different songs turned away in a row is the route`() {
        // The three songs from the capture, all of which played after the VPN exit changed.
        val detector = AuthWallDetector()

        detector.recordAuthRequired("03vjkV3I0WU")

        assertTrue(detector.recordAuthRequired("wZ-kJBmc--s"))
        assertTrue(detector.recordAuthRequired("k3eaCASt_EY"))
    }

    @Test
    fun `anything that plays clears the verdict`() {
        val detector = AuthWallDetector()
        detector.recordAuthRequired("03vjkV3I0WU")
        detector.recordAuthRequired("wZ-kJBmc--s")
        assertTrue(detector.isRouteWide())

        detector.recordPlayable()

        assertFalse(detector.isRouteWide())
        assertFalse("and the count starts over", detector.recordAuthRequired("k3eaCASt_EY"))
    }

    @Test
    fun `a song with no identifier is not counted`() {
        val detector = AuthWallDetector()

        assertFalse(detector.recordAuthRequired(null))
        assertFalse(detector.recordAuthRequired(null))
        assertFalse(detector.isRouteWide())
    }

    @Test
    fun `a new route starts clean`() {
        val detector = AuthWallDetector()
        detector.recordAuthRequired("03vjkV3I0WU")
        detector.recordAuthRequired("wZ-kJBmc--s")

        detector.forget()

        assertFalse(detector.isRouteWide())
    }
}
