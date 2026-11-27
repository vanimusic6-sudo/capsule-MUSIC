package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.playback.PlaybackFailureKind
import com.nikhil.yt.playback.PlaybackFailureClassifier
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AudioEntitlementFailureMemoryTest {
    @Test fun verdictIsScopedAndExpiresWithoutExtendingOnRead() {
        var now = 0L
        val memory = AudioEntitlementFailureMemory { now }
        memory.record("anonymous", "clip", PlaybackFailureKind.AUTH_REQUIRED)
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, memory.get("anonymous", "clip"))
        assertNull(memory.get("signed-in", "clip"))
        assertNull(memory.get("anonymous", "other"))
        now = 299_999L
        assertNotNull(memory.get("anonymous", "clip"))
        now = 300_001L
        assertNull(memory.get("anonymous", "clip"))
    }

    @Test fun explicitAgeVerdictSurvivesWrappedLoaderErrorButCdnRefusalIsNotEntitlement() {
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, terminalAudioEntitlement(YouTubeFailureKind.AGE_RESTRICTED, false))
        assertEquals(PlaybackFailureKind.AGE_RESTRICTED, terminalAudioEntitlement(YouTubeFailureKind.AGE_RESTRICTED, true))
        assertNull(terminalAudioEntitlement(YouTubeFailureKind.FORBIDDEN, false))
        assertNull(terminalAudioEntitlement(YouTubeFailureKind.TRANSIENT, false))
        assertNull(terminalAudioEntitlement(YouTubeFailureKind.LOGIN_REQUIRED, true))
        val failure = IOException("loader", AudioEntitlementException(PlaybackFailureKind.AUTH_REQUIRED))
        assertEquals(PlaybackFailureKind.AUTH_REQUIRED, PlaybackFailureClassifier.classify(failure, false))
    }
}
