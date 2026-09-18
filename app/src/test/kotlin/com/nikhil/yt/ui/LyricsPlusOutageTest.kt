package com.nikhil.yt.ui

import com.nikhil.yt.lyrics.LyricsPlusLyricsProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider that is down costs one round of requests, not one per song.
 *
 * The mirrors behind this provider are volunteer-run and go down together. With no memory of that,
 * every track opened three connections that could not be made and waited out their timeouts before
 * the provider could be passed over. A capture of ordinary listening showed eight songs and eight
 * rounds of it without a single answer, which is real network and battery spent on a service that
 * had already said nothing eight times.
 */
class LyricsPlusOutageTest {
    @Test
    fun theCooldownOutlastsASongButNotASitting() {
        val cooldown = LyricsPlusLyricsProvider.OUTAGE_COOLDOWN_MS

        assertTrue(
            "a cooldown shorter than a song is no cooldown: every track would try again",
            cooldown > 5 * 60 * 1000L,
        )
        assertTrue(
            "a mirror that comes back must not be punished for the rest of the sitting",
            cooldown <= 30 * 60 * 1000L,
        )
    }
}
