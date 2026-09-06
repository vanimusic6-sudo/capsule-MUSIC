package com.nikhil.yt.playback

internal fun forwardSeekPositionMs(
    currentPositionMs: Long,
    durationMs: Long,
    seekDeltaMs: Long = 10_000L,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val delta = seekDeltaMs.coerceAtLeast(0L)
    val target =
        if (current > Long.MAX_VALUE - delta) {
            Long.MAX_VALUE
        } else {
            current + delta
        }

    return if (durationMs >= 0L) target.coerceAtMost(durationMs) else target
}
