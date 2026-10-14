package com.nikhil.yt.playback.video

import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoModeRequestTest {
    @Test fun expiredCooldownAndIdleSelectionCanAlwaysBeRetried() {
        for (phase in listOf(CapsuleVideoPhase.REQUEST_ERROR, CapsuleVideoPhase.IDLE, CapsuleVideoPhase.UNAVAILABLE)) {
            val state = CapsuleVideoPlaybackState(preferredMode = CapsulePlaybackMode.VIDEO, phase = phase)
            assertFalse(state.videoRequestAlreadyActive)
        }
    }

    @Test fun repeatedVideoTapsDoNotStartDuplicateWorkWhileAudioAlwaysReversesTheIntent() {
        for (phase in listOf(CapsuleVideoPhase.RESOLVING, CapsuleVideoPhase.PREPARING,
            CapsuleVideoPhase.RECOVERING, CapsuleVideoPhase.PLAYING, CapsuleVideoPhase.BUFFERING)) {
            val state = CapsuleVideoPlaybackState(preferredMode = CapsulePlaybackMode.VIDEO, phase = phase)
            assertTrue(state.videoRequestAlreadyActive)
            assertFalse(state.copy(preferredMode = CapsulePlaybackMode.AUDIO).videoRequestAlreadyActive)
        }
    }

    @Test fun requestBudgetIncludesEveryFallbackAndIpcDelivery() {
        assertTrue(CapsuleVideoIpc.EXTRACTION_TIMEOUT_MS >= 4_000L + 4_000L + 10_000L + 10_000L + 4_000L)
        assertTrue(CapsuleVideoIpc.REQUEST_TIMEOUT_MS > CapsuleVideoIpc.EXTRACTION_TIMEOUT_MS)
        assertTrue(CapsuleVideoIpc.SONG_TIMEOUT_MS > CapsuleVideoIpc.REQUEST_TIMEOUT_MS)
    }
}
