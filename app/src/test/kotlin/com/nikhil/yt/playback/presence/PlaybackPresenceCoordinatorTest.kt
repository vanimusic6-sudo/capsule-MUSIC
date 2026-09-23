package com.nikhil.yt.playback.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPresenceCoordinatorTest {
    @Test
    fun immediateGateAcceptsFirstUpdateAndDebouncesBurst() {
        var now = 100_000L
        val gate = ImmediatePresenceUpdateGate(
            minIntervalMs = 20_000L,
            nowMillis = { now },
        )

        assertTrue(gate.tryAcquire())
        now += 1_000L
        assertFalse(gate.tryAcquire())
        now += 19_000L
        assertFalse(gate.tryAcquire())
        now += 1L
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun listenBrainzDoesNotDependOnDiscordConfiguration() {
        assertTrue(
            shouldSubmitListenBrainzPlayingNow(
                enabled = true,
                token = "listenbrainz-token",
                songAvailable = true,
            ),
        )
    }

    @Test
    fun listenBrainzRequiresItsOwnTokenAndSong() {
        assertFalse(
            shouldSubmitListenBrainzPlayingNow(
                enabled = true,
                token = "",
                songAvailable = true,
            ),
        )
        assertFalse(
            shouldSubmitListenBrainzPlayingNow(
                enabled = true,
                token = "listenbrainz-token",
                songAvailable = false,
            ),
        )
        assertFalse(
            shouldSubmitListenBrainzPlayingNow(
                enabled = false,
                token = "listenbrainz-token",
                songAvailable = true,
            ),
        )
    }
}
