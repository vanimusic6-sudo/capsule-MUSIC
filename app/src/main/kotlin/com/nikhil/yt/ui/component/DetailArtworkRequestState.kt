package com.nikhil.yt.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class DetailArtworkAttempt(val source: String, val round: Int)

/** Bounded fallback requests. A decoded cover is pinned until the collection changes. */
internal class DetailArtworkRequestState(initialCandidates: List<String>) {
    private var candidates = initialCandidates
    private val tried = mutableSetOf<String>()
    private var round = 0

    var attempt by mutableStateOf(initialCandidates.firstOrNull()?.let { DetailArtworkAttempt(it, 0) })
        private set
    var loaded by mutableStateOf(false)
        private set

    fun updateCandidates(updated: List<String>) {
        if (loaded || updated == candidates) return
        val hasNewSource = updated.any { it !in candidates }
        candidates = updated
        if (attempt == null && hasNewSource) {
            round = 0
            tried.clear()
            attempt = candidates.firstOrNull()?.let { DetailArtworkAttempt(it, round) }
        }
    }

    fun succeed(completed: DetailArtworkAttempt) {
        if (attempt == completed) loaded = true
    }

    fun fail(completed: DetailArtworkAttempt) {
        // Ignore a cancelled request completing after its timeout or navigation.
        if (loaded || attempt != completed) return
        tried += completed.source
        var next = candidates.firstOrNull { it !in tried }
        if (next == null && round < 2 && candidates.isNotEmpty()) {
            round++
            tried.clear()
            next = candidates.first()
        }
        attempt = next?.let { DetailArtworkAttempt(it, round) }
    }
}
