package com.nikhil.yt.ui

import com.nikhil.yt.lyrics.LyricsPlusLyricsProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider that is down costs one round of requests, not one per song.
 *
 * The mirrors behind this provider are volunteer-run and go down together. With no memory of that,
 * every track opened slow connections that could not be made and waited out their timeouts before
 * the provider could be passed over. The old 10-minute cool-down then hid
 * restored mirrors and made the provider appear permanently unavailable.
 */
class LyricsPlusOutageTest {
    @Test
    fun theCooldownOutlastsASongButNotASitting() {
        val cooldown = LyricsPlusLyricsProvider.OUTAGE_COOLDOWN_MS

        assertTrue(
            "A true outage must not trigger another whole mirror fan-out on every track",
            cooldown >= 60 * 1000L,
        )
        assertTrue(
            "An outage must be rechecked soon, not hidden by a ten-minute false-negative",
            cooldown <= 3 * 60 * 1000L,
        )
    }
}
