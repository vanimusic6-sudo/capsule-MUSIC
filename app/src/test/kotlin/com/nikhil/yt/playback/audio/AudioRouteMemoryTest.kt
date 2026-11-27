package com.nikhil.yt.playback.audio

import org.junit.Assert.*
import org.junit.Test

class AudioRouteMemoryTest {
    private val host = "rr1---sn-example.googlevideo.com"
    @Test fun twoIndependentOpusRefusalsPreferAacOnlyForThatSongAndSession() {
        val m = AudioRouteMemory()
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "first")
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "first")
        assertFalse(m.advice("wifi", "song").preferAac)
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "second")
        assertTrue(m.advice("wifi", "song").preferAac)
        assertFalse(m.advice("wifi", "other").preferAac)
        assertFalse(m.advice("mobile", "song").preferAac)
    }

    @Test fun confirmedAlternativeReordersOnlyConfiguredClients() {
        val m = AudioRouteMemory()
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "one")
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "two")
        m.accepted("wifi", "song", "VISIONOS_0_1", 140, host)
        val advice = m.advice("wifi", "song")
        assertTrue(advice.preferAac)
        assertEquals(listOf("VISIONOS_0_1", "WEB_REMIX"), advice.prioritize(listOf("WEB_REMIX", "VISIONOS_0_1")))
        assertEquals(listOf("WEB_REMIX"), advice.prioritize(listOf("WEB_REMIX")))
        m.refused("wifi", "song", "VISIONOS_0_1", 140, host, "three")
        assertNull(m.advice("wifi", "song").acceptedClient)
    }

    @Test fun successfulOpusRouteDoesNotForceAacAndHistoryExpires() {
        var now = 0L
        val m = AudioRouteMemory({ now }, ttlMs = 100L)
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "one")
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "two")
        m.accepted("wifi", "song", "VISIONOS_0_1", 251, host)
        assertFalse(m.advice("wifi", "song").preferAac)
        now = 99
        assertNotNull(m.advice("wifi", "song").acceptedClient)
        now = 100
        assertEquals(AudioRouteAdvice(), m.advice("wifi", "song"))
    }

    @Test fun pipePipeProbeNeedsDistinctRefusalsAndIsBoundedToTrackAndSession() {
        var now = 0L
        val m = AudioRouteMemory({ now })
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "one")
        assertFalse(m.claimPipePipe("wifi", "song"))
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "two")
        assertFalse(m.claimPipePipe("wifi", "other"))
        assertFalse(m.claimPipePipe("mobile", "song"))
        assertTrue(m.claimPipePipe("wifi", "song"))
        assertFalse(m.claimPipePipe("wifi", "song"))
        now = 60_001L
        assertTrue(m.claimPipePipe("wifi", "song"))
        m.refused("wifi", "song", PIPE_AUDIO_CLIENT, 140, host, "three")
        now += 60_001L
        assertFalse(m.claimPipePipe("wifi", "song"))
    }

    @Test fun successfulPipePipeCanBeReusedButNotAfterCdnRefusal() {
        val m = AudioRouteMemory()
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "one")
        m.refused("wifi", "song", "WEB_REMIX", 251, host, "two")
        assertTrue(m.claimPipePipe("wifi", "song"))
        m.accepted("wifi", "song", PIPE_AUDIO_CLIENT, 140, host)
        assertTrue(m.claimPipePipe("wifi", "song"))
        m.refused("wifi", "song", PIPE_AUDIO_CLIENT, 140, host, "three")
        assertFalse(m.claimPipePipe("wifi", "song"))
    }

    @Test fun noLearningForUnrelatedHostOrHealthyTrack() {
        val m = AudioRouteMemory()
        m.refused("wifi", "song", "WEB_REMIX", 251, "evil.test", "one")
        m.accepted("wifi", "song", "VISIONOS_0_1", 251, host)
        assertEquals(AudioRouteAdvice(), m.advice("wifi", "song"))
    }
}
