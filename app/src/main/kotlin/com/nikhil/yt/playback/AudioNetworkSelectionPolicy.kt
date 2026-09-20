package com.nikhil.yt.playback

/**
 * A queue neighbour must never pre-empt the selected song's initial /player and first CDN open.
 * An item just skipped backwards can become "upcoming" again while the new song still buffers.
 * Only genuinely steady foreground playback permits background look-ahead.
 */
internal fun canResolveUpcomingAudioWhile(
    currentIsPlaying: Boolean,
    currentPositionMs: Long,
    currentIsVideo: Boolean,
    minimumPlayedMs: Long = AUDIO_PREFETCH_MIN_CURRENT_PROGRESS_MS,
): Boolean =
    currentIsPlaying &&
        currentPositionMs >= minimumPlayedMs &&
        !currentIsVideo
