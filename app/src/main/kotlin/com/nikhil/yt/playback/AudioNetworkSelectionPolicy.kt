package com.nikhil.yt.playback

/**
 * A queue neighbour must never pre-empt the selected song's initial /player and first CDN open.
 * An item just skipped backwards can become "upcoming" again while the new song still buffers.
 * Only genuinely steady foreground playback permits background look-ahead.
 */
/**
 * Only optional /player look-ahead needs a safe foreground buffer. Near the
 * natural end of a known song a smaller buffer suffices if it covers ALL
 * remaining playback. Unknown duration requires the full runway.
 * Never gate Media3's real next-period loader (below).
 */
internal const val AUDIO_PREFETCH_SAFE_BUFFER_MS = 12_000L

internal fun audioPrefetchRequiredBufferMs(
    currentPositionMs: Long,
    currentDurationMs: Long,
): Long = if (currentDurationMs > 0L) {
    val playedMs = currentPositionMs.coerceAtLeast(0L)
    // A corrupt/beyond-end position must not wrap Long subtraction.
    val remainingMs = if (playedMs >= currentDurationMs) 0L else currentDurationMs - playedMs
    minOf(AUDIO_PREFETCH_SAFE_BUFFER_MS, remainingMs)
} else AUDIO_PREFETCH_SAFE_BUFFER_MS

internal fun canResolveUpcomingAudioWhile(
    currentIsPlaying: Boolean,
    currentPositionMs: Long,
    currentIsVideo: Boolean,
    currentBufferedAheadMs: Long = 0L,
    currentDurationMs: Long = -1L,
    minimumPlayedMs: Long = AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS,
): Boolean {
    if (!currentIsPlaying || currentIsVideo || currentPositionMs < minimumPlayedMs) return false
    val required = audioPrefetchRequiredBufferMs(currentPositionMs, currentDurationMs)
    return required > 0L && currentBufferedAheadMs >= required
}

/** Actual Media3 period loading is not speculative prefetch. Still reject abandoned queue items. */
internal fun isAudioLoaderSelectionRelevant(
    mediaId: String, currentId: String?, upcomingIds: List<String>, currentIsVideo: Boolean,
): Boolean = mediaId == currentId || (!currentIsVideo && mediaId in upcomingIds)
