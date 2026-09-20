package com.nikhil.yt.playback

/**
 * Only intervene when audio is genuinely starved, not while the user is paused, seeking,
 * playing video, offline, or waiting for an ordinary CDN open. Both samples must belong
 * to the same media selection; MusicService enforces that before using this predicate.
 */
internal object AudioBufferStallPolicy {
    const val INITIAL_WAIT_MS = 45_000L
    const val REBUFFER_WAIT_MS = 20_000L
    const val SAMPLE_WINDOW_MS = 4_000L
    const val MAX_BUFFER_AHEAD_MS = 8_000L
    const val MIN_BUFFER_GROWTH_MS = 2_000L
    const val MAX_AUTOMATIC_REFRESHES_PER_TRACK = 2

    fun shouldRefresh(
        buffering: Boolean,
        playWhenReady: Boolean,
        connected: Boolean,
        blocked: Boolean,
        isVideo: Boolean,
        bufferedAheadMs: Long,
        bufferedGrowthMs: Long,
    ): Boolean =
        buffering && playWhenReady && connected && !blocked && !isVideo &&
            bufferedAheadMs in 0 until MAX_BUFFER_AHEAD_MS &&
            bufferedGrowthMs < MIN_BUFFER_GROWTH_MS
}
