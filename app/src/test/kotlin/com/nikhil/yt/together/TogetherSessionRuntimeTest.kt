package com.nikhil.yt.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TogetherSessionRuntimeTest {
    private fun runtime() = TogetherSessionRuntime { _, _ -> }

    @Test
    fun remoteApplySuppressesImmediateSeekAndPlaybackEchoes() {
        val runtime = runtime()
        runtime.lastRemoteAppliedIndex = 4
        runtime.lastRemoteAppliedPlayWhenReady = true

        runtime.beginRemoteApply(nowElapsedMs = 1_000L)

        assertTrue(runtime.shouldSuppressSeekEcho(nowElapsedMs = 1_100L, index = 4))
        assertTrue(runtime.shouldSuppressPlayWhenReadyEcho(nowElapsedMs = 1_100L, playWhenReady = true))

        runtime.finishRemoteApply()

        assertTrue(runtime.shouldSuppressSeekEcho(nowElapsedMs = 1_200L, index = 4))
        assertTrue(runtime.shouldSuppressPlayWhenReadyEcho(nowElapsedMs = 1_200L, playWhenReady = true))
        assertFalse(runtime.shouldSuppressSeekEcho(nowElapsedMs = 1_200L, index = 3))
        assertFalse(runtime.shouldSuppressPlayWhenReadyEcho(nowElapsedMs = 1_200L, playWhenReady = false))
    }

    @Test
    fun echoSuppressionExpiresAndResetClearsBookkeeping() {
        val runtime = runtime()
        runtime.lastRemoteAppliedIndex = 2
        runtime.lastRemoteAppliedPlayWhenReady = false
        runtime.lastAppliedRoomStateSentAtElapsedMs = 99L
        runtime.lastAppliedQueueHash = "queue"
        runtime.selfParticipantId = "guest"
        runtime.isOnlineSession = true

        runtime.beginRemoteApply(nowElapsedMs = 10_000L)
        runtime.finishRemoteApply()

        assertFalse(runtime.shouldSuppressSeekEcho(nowElapsedMs = 10_451L, index = 2))
        assertFalse(runtime.shouldSuppressPlayWhenReadyEcho(nowElapsedMs = 10_451L, playWhenReady = false))

        runtime.resetBookkeeping()

        assertNull(runtime.lastRemoteAppliedPlayWhenReady)
        assertEquals(-1, runtime.lastRemoteAppliedIndex)
        assertEquals(0L, runtime.lastAppliedRoomStateSentAtElapsedMs)
        assertNull(runtime.lastAppliedQueueHash)
        assertNull(runtime.selfParticipantId)
        assertFalse(runtime.isOnlineSession)
        assertFalse(runtime.applyingRemote)
        assertEquals(0L, runtime.suppressEchoUntilElapsedMs)
    }
}
