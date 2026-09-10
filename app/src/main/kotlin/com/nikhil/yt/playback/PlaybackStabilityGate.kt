package com.nikhil.yt.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

internal const val PLAYBACK_RESOLVE_STABILITY_DELAY_MS = 250L
internal const val PREFETCH_RESOLVE_STABILITY_DELAY_MS = 800L

/** Debounces network work shared by the loader and prefetch after queue navigation. */
internal class PlaybackStabilityGate(
    private val stabilityDelayMs: Long = PREFETCH_RESOLVE_STABILITY_DELAY_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile
    private var selectionChangedAtMs = nowMs()
    private val selectionGeneration = MutableStateFlow(0L)

    fun onSelectionChanged() {
        selectionChangedAtMs = nowMs()
        selectionGeneration.update { it + 1L }
    }

    /**
     * Wait until the current selection has been quiet for the requested delay.
     *
     * [requiredDelayMs] is re-evaluated whenever selection changes. This lets a track that was
     * only PREFETCH (800 ms debounce) become PLAYBACK (250 ms debounce) immediately instead of
     * remaining stuck behind the old prefetch timer.
     */
    suspend fun awaitStable(
        requiredDelayMs: suspend () -> Long = { stabilityDelayMs },
        isRelevant: suspend () -> Boolean,
    ) {
        while (true) {
            val generation = selectionGeneration.value
            val changedAt = selectionChangedAtMs
            val required = requiredDelayMs().coerceAtLeast(0L)
            val remaining = required - (nowMs() - changedAt)
            if (remaining > 0L) {
                // Wake as soon as queue selection changes so a promoted prefetch can adopt the
                // shorter PLAYBACK delay without waiting for its previous 800 ms timer to expire.
                withTimeoutOrNull(remaining) {
                    selectionGeneration.first { it != generation }
                }
                continue
            }

            if (!isRelevant()) throw CancellationException("Track is no longer near playback")
            // The selection can change while the caller checks it on the player thread.
            if (changedAt == selectionChangedAtMs && generation == selectionGeneration.value) return
        }
    }
}
