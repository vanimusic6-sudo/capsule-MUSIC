package com.nikhil.yt.playback

/**
 * Only intervene when audio is genuinely starved, not while the user is paused, seeking,
 * playing video, offline, or waiting for an ordinary CDN open. Both samples must belong
 * to the same media selection; MusicService enforces that before using this predicate.
 */
internal object AudioBufferStallPolicy {
    // A source which delivered no playable audio for 20s is not a normal
    // cold start. A further 3s buffer-growth sample avoids penalizing a
    // slow-but-progressing URL resolve or CDN connection.
    const val INITIAL_WAIT_MS = 20_000L
    // Mid-song stalls have an existing decoder and buffer; recover sooner.
    const val REBUFFER_WAIT_MS = 10_000L
    const val SAMPLE_WINDOW_MS = 3_000L
    const val MAX_BUFFER_AHEAD_MS = 8_000L
    const val MIN_BUFFER_GROWTH_MS = 2_000L
    const val MAX_AUTOMATIC_REFRESHES_PER_TRACK = 2

    // A real rebuffer with almost no growth on an hour-long track may be a
    // paced CDN body rather than a rejected /player response. Promote Direct
    // clients one at a time; after two independently observed stalls InnerTubeX
    // may negotiate a SABR transport. Never rotate on ordinary short songs,
    // local/offline playback, or the explicit SABR diagnostic.
    const val LONG_FORM_THRESHOLD_BYTES = 12L * 1024 * 1024

    fun shouldRotateLongFormClient(
        contentLengthBytes: Long?,
        sabrSelected: Boolean,
        forceSabr: Boolean,
        observedStalls: Int,
    ): Boolean =
        (contentLengthBytes ?: 0L) >= LONG_FORM_THRESHOLD_BYTES &&
            !sabrSelected && !forceSabr &&
            observedStalls in 0 until MAX_AUTOMATIC_REFRESHES_PER_TRACK

    /**
     * Long-form slow CDN bodies warrant an alternate client immediately.
     * A short song which never reached READY must also be able to escape a
     * broken profile: refreshing the same client twice can otherwise return
     * the same non-playable stream indefinitely. For a short mid-song
     * rebuffer, preserve the first same-client recovery.
     */
    fun shouldRotateClientAfterStall(
        contentLengthBytes: Long?,
        sabrSelected: Boolean,
        forceSabr: Boolean,
        observedStalls: Int,
        hasEverBeenReady: Boolean,
    ): Boolean =
        !sabrSelected && !forceSabr &&
            observedStalls in 0 until MAX_AUTOMATIC_REFRESHES_PER_TRACK &&
            (!hasEverBeenReady ||
                (contentLengthBytes ?: 0L) >= LONG_FORM_THRESHOLD_BYTES ||
                observedStalls > 0)

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
            bufferedAheadMs >= 0L && bufferedAheadMs < MAX_BUFFER_AHEAD_MS &&
            bufferedGrowthMs < MIN_BUFFER_GROWTH_MS
}
