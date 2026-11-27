package com.nikhil.yt.playback

/**
 * Only intervene when audio is genuinely starved, not while the user is paused, seeking,
 * playing video, offline, or waiting for an ordinary CDN open. Both samples must belong
 * to the same media selection; MusicService enforces that before using this predicate.
 */
internal object AudioBufferStallPolicy {
    // Media3's CDN-open timeout already bounds the first broken socket; do not
    // leave a no-byte BUFFERING state idle for another 20 seconds. Sample real
    // buffer growth before touching the existing bounded recovery path.
    const val INITIAL_WAIT_MS = 12_000L
    // A mid-song stall has an existing decoder. Recover before it feels permanent.
    const val REBUFFER_WAIT_MS = 6_000L
    const val SAMPLE_WINDOW_MS = 2_000L
    const val MAX_BUFFER_AHEAD_MS = 8_000L
    // A source may make measurable progress in each sample and still never
    // supply enough usable media to leave BUFFERING. Bound that episode too.
    const val MAX_STARVED_BUFFERING_MS = 30_000L
    // Require meaningful progress, but tolerate a temporarily slow mobile CDN.
    const val MIN_BUFFER_GROWTH_MS = 1_200L
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

    /** Media3 totalBufferedDuration includes subsequent media items. */
    fun currentItemHeadroomMs(bufferedPositionMs: Long, currentPositionMs: Long): Long =
        if (bufferedPositionMs < 0L || currentPositionMs < 0L || bufferedPositionMs <= currentPositionMs) 0L
        else bufferedPositionMs - currentPositionMs

    fun shouldRefresh(
        buffering: Boolean,
        playWhenReady: Boolean,
        connected: Boolean,
        blocked: Boolean,
        isVideo: Boolean,
        bufferedAheadMs: Long,
        bufferedGrowthMs: Long,
        continuousBufferingMs: Long = 0L,
    ): Boolean =
        buffering && playWhenReady && connected && !blocked && !isVideo &&
            bufferedAheadMs >= 0L && bufferedAheadMs < MAX_BUFFER_AHEAD_MS &&
            (bufferedGrowthMs < MIN_BUFFER_GROWTH_MS ||
                continuousBufferingMs >= MAX_STARVED_BUFFERING_MS)
}
