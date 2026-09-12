package com.nikhil.yt.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

internal const val PLAYBACK_RESOLVE_STABILITY_DELAY_MS = 250L
internal const val PREFETCH_RESOLVE_STABILITY_DELAY_MS = 1_500L
internal const val RAPID_SKIP_MAX_GAP_MS = 400L
internal const val RAPID_SKIP_TRIGGER_COUNT = 3
internal const val RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS = 650L

/** Debounces network work shared by the loader and prefetch after queue navigation. */
internal class PlaybackStabilityGate(
    private val stabilityDelayMs: Long = PREFETCH_RESOLVE_STABILITY_DELAY_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile
    private var selectionChangedAtMs = nowMs()

    @Volatile
    private var hasObservedSelectionChange = false

    @Volatile
    private var rapidSelectionStreak = 0

    private val selectionGeneration = MutableStateFlow(0L)

    fun onSelectionChanged() {
        val now = nowMs()
        val previous = selectionChangedAtMs
        rapidSelectionStreak =
            if (hasObservedSelectionChange && now - previous in 0..RAPID_SKIP_MAX_GAP_MS) {
                rapidSelectionStreak + 1
            } else {
                1
            }
        hasObservedSelectionChange = true
        selectionChangedAtMs = now
        selectionGeneration.update { it + 1L }
    }

    /**
     * Hold the real CDN open at the same selection boundary as the resolver.
     *
     * This matters when prefetch has already cached a signed URL: without this guard the
     * ResolvingDataSource can skip the resolver gate and OkHttp can put a request on the wire
     * for an intermediate track before Media3 cancels it. A normal transition keeps the 250 ms
     * grace window; a rapid skip burst inherits the adaptive 650 ms settle window.
     */
    suspend fun awaitNetworkOpenStable(isRelevant: suspend () -> Boolean) {
        awaitStable(
            requiredDelayMs = { PLAYBACK_RESOLVE_STABILITY_DELAY_MS },
            isRelevant = isRelevant,
        )
    }

    /**
     * Wait until the current selection has been quiet for the requested delay.
     *
     * [requiredDelayMs] is re-evaluated whenever selection changes. This lets a track that was
     * only PREFETCH (800 ms debounce) become PLAYBACK (250 ms debounce) immediately instead of
     * remaining stuck behind the old prefetch timer.
     *
     * Three or more fast selection changes are treated as an intentional scrub through the queue.
     * During that burst only PLAYBACK work gets a slightly longer 650 ms settle window. This keeps
     * intermediate tracks from opening CDN connections while preserving the normal 250 ms response
     * for a single skip and the existing 800 ms prefetch debounce.
     */
    suspend fun awaitStable(
        requiredDelayMs: suspend () -> Long = { stabilityDelayMs },
        isRelevant: suspend () -> Boolean,
    ) {
        while (true) {
            val generation = selectionGeneration.value
            val changedAt = selectionChangedAtMs
            val requested = requiredDelayMs().coerceAtLeast(0L)
            val required =
                if (
                    requested == PLAYBACK_RESOLVE_STABILITY_DELAY_MS &&
                    rapidSelectionStreak >= RAPID_SKIP_TRIGGER_COUNT
                ) {
                    maxOf(requested, RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS)
                } else {
                    requested
                }
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
