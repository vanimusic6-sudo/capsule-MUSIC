package com.nikhil.yt.playback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Retain a state change across the check/suspend boundary, coalescing event bursts. */
internal class PlaybackEventGate {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    fun signal() { changes.trySend(Unit) }
    suspend fun awaitChange(timeoutMs: Long? = null) {
        if (timeoutMs == null) changes.receive()
        else withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { changes.receive() }
    }
}
