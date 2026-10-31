package com.nikhil.yt.playback

import kotlinx.coroutines.delay

/**
 * Suspend a download's *resolver*, not the foreground player, for an acknowledged
 * YouTube rate limit or the download-only challenge/cooldown.
 *
 * A fixed 30-second gate used to throw while the legitimate AUDIO 429 breaker was
 * still active for ten minutes. Media3 would then be given a download failure
 * even though nothing was wrong with the partially cached file.
 *
 * We intentionally leave the wait cancellation-aware: stopping/removing the
 * download cancels the owner's coroutine. No threads spin, no YouTube request is
 * sent while a cooldown is active, and the underlying resolver's priority queue
 * remains free for normal playback.
 */
internal suspend fun awaitDownloadCooldownWindow(
    nowMs: () -> Long,
    globalRemainingMs: (Long) -> Long,
    downloadRemainingMs: () -> Long,
    localCooldownUntilMs: () -> Long,
    sliceMs: Long = 2_000L,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    require(sliceMs > 0L)
    while (true) {
        val now = nowMs()
        val remaining =
            maxOf(
                globalRemainingMs(now),
                downloadRemainingMs(),
                (localCooldownUntilMs() - now).coerceAtLeast(0L),
            )
        if (remaining <= 0L) return
        sleep(minOf(remaining, sliceMs))
    }
}
