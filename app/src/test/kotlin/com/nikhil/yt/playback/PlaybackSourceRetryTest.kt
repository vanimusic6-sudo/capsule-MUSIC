@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSourceRetryTest {
    private class Decoder(val state: Int) {
        var intent = false
        var prepares = 0
        var replacements = 0
        var seeks = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getCurrentMediaItemIndex" -> 3
                "getCurrentPosition" -> 12_345L
                "getPlayWhenReady" -> intent
                "setPlayWhenReady" -> { intent = args!![0] as Boolean; null }
                "prepare" -> { prepares++; null }
                "seekTo" -> { seeks++; null }
                "setMediaItems", "setMediaItem", "clearMediaItems" -> { replacements++; null }
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player
    }

    @Test fun failedSourceRetriesWithoutRebuildingQueueOrLosingPlayIntent() {
        val decoder = Decoder(Player.STATE_IDLE)
        var rebuilt = false
        prepareAudioSourceRetry(decoder.player, 3, 12_345L, true, true) { rebuilt = true }
        assertFalse(rebuilt)
        assertEquals(0, decoder.replacements)
        assertEquals(0, decoder.seeks)
        assertEquals(1, decoder.prepares)
        assertTrue(decoder.intent)
    }

    @Test fun retryDoesNotResumeAnExplicitlyPausedTrack() {
        val decoder = Decoder(Player.STATE_IDLE).apply { intent = true }
        prepareAudioSourceRetry(decoder.player, 3, 12_345L, false, true) { fail("Unexpected rebuild") }
        assertFalse(decoder.intent)
        assertEquals(1, decoder.prepares)
    }

    @Test fun stuckActiveSourceStillUsesFullRecovery() {
        val decoder = Decoder(Player.STATE_BUFFERING)
        var rebuilt = false
        prepareAudioSourceRetry(decoder.player, 3, 12_345L, true, true) { rebuilt = true }
        assertTrue(rebuilt)
        assertEquals(0, decoder.prepares)
    }

    @Test fun unknownOrFixedDeviceVolumeCannotPausePlayback() {
        assertNull(isSystemMediaMuted(null, null, false))
        assertNull(isSystemMediaMuted(-1, false, false))
        assertEquals(false, isSystemMediaMuted(0, true, true))
        assertEquals(false, isSystemMediaMuted(5, false, false))
        assertEquals(true, isSystemMediaMuted(0, false, false))
        assertEquals(true, isSystemMediaMuted(5, true, false))
    }
}
