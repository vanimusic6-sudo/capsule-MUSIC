/*
 * Velune - by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

/**
 * Stops automatic queue traversal after several different tracks fail without
 * any track reaching READY in between.
 *
 * Duplicate callbacks for the same media item never advance the queue again.
 * Once open, the guard stays open until a source has played long enough to be
 * considered healthy.
 */
internal class ConsecutiveTrackFailureGuard(
    private val maxConsecutiveFailures: Int,
) {
    init {
        require(maxConsecutiveFailures > 0)
    }

    private var lastFailedMediaId: String? = null

    var failureCount: Int = 0
        private set

    var isOpen: Boolean = false
        private set

    /** Returns true only when it is safe to auto-skip exactly one item. */
    fun recordFailure(mediaId: String?): Boolean {
        if (isOpen) return false

        val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedMediaId == null || normalizedMediaId == lastFailedMediaId) {
            return false
        }

        lastFailedMediaId = normalizedMediaId
        failureCount += 1
        if (failureCount >= maxConsecutiveFailures) {
            isOpen = true
            return false
        }

        return true
    }

    fun onHealthyPlayback() {
        reset()
    }

    fun reset() {
        lastFailedMediaId = null
        failureCount = 0
        isOpen = false
    }
}
