package com.nikhil.yt.playback.audio

import org.junit.Assert.*
import org.junit.Test

class AudioAccessDisciplineTest {
    @Test fun explicitRefusalDoesNotBecomeARequestOnManualRetryOrAnotherTrack() {
        var now = 0L
        val policy = AudioAccessDiscipline({ now })
        policy.recordTrack("a")
        assertEquals(AudioAccessStopReason.TRACK_REFUSED, policy.stopFor("a")?.reason)
        assertNull(policy.stopFor("b"))
        now = 299_999L
        policy.recordTrack("a") // duplicate callback must not extend the quiet interval
        assertEquals(1L, policy.stopFor("a")?.remainingMs)
        now++
        assertNull(policy.stopFor("a"))
    }

    @Test fun threeRealWireRefusalsStopNewOnlineWorkAndExpireWithoutAProbe() {
        var now = 0L
        val policy = AudioAccessDiscipline({ now })
        repeat(2) { policy.recordWireRefusal(); now += 1_000L }
        assertNull(policy.stopFor())
        policy.recordWireRefusal()
        assertEquals(AudioAccessStopReason.SERVER_COOLDOWN, policy.stopFor("unrelated")?.reason)
        now += 119_999L
        policy.recordWireRefusal() // in-flight response does not endlessly extend a burst
        assertEquals(1L, policy.globalRemainingMs())
        now++
        assertNull(policy.stopFor())
    }

    @Test fun oldRefusalsDoNotCreateAFalseNetworkWideCooldown() {
        var now = 0L
        val policy = AudioAccessDiscipline({ now })
        repeat(8) { policy.recordWireRefusal(); now += 60_000L }
        assertNull(policy.stopFor())
    }

    @Test fun serverChallengeStopsBothForegroundAndBackgroundWork() {
        var now = 0L
        val policy = AudioAccessDiscipline({ now })
        policy.recordChallenge()
        assertNotNull(policy.stopFor())
        assertNotNull(policy.stopFor("a"))
        now += 120_000L
        assertNull(policy.stopFor())
    }
}
