package com.nikhil.yt.playback

/** One budget shared by network retries and signed-URL refreshes. Main-thread owned. */
internal class PlaybackRetryBudget(
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 1_500L,
) {
    private val attempts = linkedMapOf<String, Int>()

    fun nextDelayMs(mediaId: String): Long? {
        val count = attempts[mediaId] ?: 0
        if (count >= maxAttempts) return null
        attempts[mediaId] = count + 1
        if (attempts.size > 128) attempts.remove(attempts.keys.first())
        return (initialDelayMs * (1L shl count.coerceAtMost(10))).coerceAtMost(30_000L)
    }

    // Only explicit user actions, a changed route, or sustained playback reset the budget.
    fun reset(mediaId: String) { attempts.remove(mediaId) }
    fun clear() { attempts.clear() }
}
