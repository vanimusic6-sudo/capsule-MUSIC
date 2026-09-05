package com.nikhil.yt.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Debounces network work shared by the loader and prefetch after queue navigation. */
internal class PlaybackStabilityGate(
    private val stabilityDelayMs: Long = 800L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile
    private var selectionChangedAtMs = nowMs()

    fun onSelectionChanged() {
        selectionChangedAtMs = nowMs()
    }

    suspend fun awaitStable(isRelevant: suspend () -> Boolean) {
        while (true) {
            val changedAt = selectionChangedAtMs
            val remaining = stabilityDelayMs - (nowMs() - changedAt)
            if (remaining > 0L) {
                delay(remaining)
                continue
            }

            if (!isRelevant()) throw CancellationException("Track is no longer near playback")
            // The selection can change while the caller checks it on the player thread.
            if (changedAt == selectionChangedAtMs) return
        }
    }
}
