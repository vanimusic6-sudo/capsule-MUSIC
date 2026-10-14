/* Capsule MUSIC — isolated audio/video timeline boundaries. GPL-3.0. */
package com.nikhil.yt.playback.video

import androidx.media3.common.C

/** Unknown and zero durations are never treated as known end-of-stream. */
internal fun capsuleKnownDuration(durationMs: Long): Long? =
    durationMs.takeIf { it > 0L && it != C.TIME_UNSET }

/** UI / generic seek: an exact end is meaningful to the active player. */
internal fun capsuleClampSeek(positionMs: Long, durationMs: Long): Long {
    val position = positionMs.coerceAtLeast(0L)
    return capsuleKnownDuration(durationMs)?.let { position.coerceIn(0L, it) } ?: position
}

/** Decoder seek: do not ask Media3 to decode a nonexistent frame at exactly EOF. */
internal fun capsuleClampDecoderSeek(positionMs: Long, durationMs: Long): Long {
    val duration = capsuleKnownDuration(durationMs) ?: return positionMs.coerceAtLeast(0L)
    return positionMs.coerceIn(0L, duration - 1L)
}

/** VIDEO mode entered from an AUDIO position beyond its shorter clip starts at 0. */
internal fun capsuleVideoStartupSeek(audioPositionMs: Long, videoDurationMs: Long): Long {
    val duration = capsuleKnownDuration(videoDurationMs)
    return if (duration != null && audioPositionMs >= duration) 0L
    else capsuleClampDecoderSeek(audioPositionMs, videoDurationMs)
}

/** A user seek at or beyond VIDEO's end is a completed track, not a restart. */
internal fun capsuleVideoSeekCompletes(positionMs: Long, videoDurationMs: Long): Boolean =
    capsuleKnownDuration(videoDurationMs)?.let { positionMs >= it } ?: false
