/*
 * Capsule MUSIC
 * YouTube audio/video mode state.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

import androidx.media3.common.Player

enum class CapsulePlaybackMode {
    AUDIO,
    VIDEO,
}

enum class CapsuleVideoPhase {
    IDLE,
    RESOLVING,
    PREPARING,
    PLAYING,
    BUFFERING,
    RECOVERING,

    /** No trustworthy official clip was found for this song. */
    UNAVAILABLE,

    /** A network / YouTube / stream request failed. The clip may still exist. */
    REQUEST_ERROR,
}

/**
 * [preferredMode] is the user's choice for the CURRENT track only.
 *
 * VIDEO is intentionally not sticky across queue transitions. MusicService
 * resets the next/previous/selected track to AUDIO and a fresh VIDEO request
 * happens only after the user explicitly presses VIDEO again.
 *
 * [mode] is what is actually playing right now.
 */
data class CapsuleVideoPlaybackState(
    val preferredMode: CapsulePlaybackMode = CapsulePlaybackMode.AUDIO,
    val mode: CapsulePlaybackMode = CapsulePlaybackMode.AUDIO,
    val phase: CapsuleVideoPhase = CapsuleVideoPhase.IDLE,
    val mediaId: String? = null,
    val videoId: String? = null,
    val qualityLabel: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val firstFrameReady: Boolean = false,
    /** A prepared decoder for this track can be resumed without a loading indicator. */
    val preparedVideoReady: Boolean = false,
    val message: String? = null,
    val requestBlockedUntilMs: Long = 0L,
    val requestBlockReason: String? = null,
) {
    val needsSurface: Boolean get() = mode == CapsulePlaybackMode.VIDEO &&
        phase in setOf(CapsuleVideoPhase.PREPARING, CapsuleVideoPhase.PLAYING, CapsuleVideoPhase.BUFFERING, CapsuleVideoPhase.RECOVERING)
    // Preparing an attached decoder is distinct from revealing its window.
    val presentingVideo: Boolean get() = needsSurface && firstFrameReady
}

internal fun CapsuleVideoPlaybackState.playbackStateOr(musicState: Int): Int {
    if (!presentingVideo) return musicState
    return when (phase) {
        CapsuleVideoPhase.PLAYING -> Player.STATE_READY
        CapsuleVideoPhase.BUFFERING, CapsuleVideoPhase.PREPARING, CapsuleVideoPhase.RECOVERING -> Player.STATE_BUFFERING
        else -> musicState
    }
}

const val CAPSULE_VIDEO_SCHEME = "capsule-video"
const val CAPSULE_VIDEO_CACHE_PREFIX = "capsule:video:"
const val CAPSULE_VIDEO_STREAM_CACHE_PREFIX = "capsule:video-stream:"

/**
 * VIDEO N/A is a confirmed result for this track, not a new search prompt every
 * time the user moves the switch. A server cooldown also cannot be retried just
 * by briefly selecting AUDIO. The service resets this state on track changes.
 */
internal fun CapsuleVideoPlaybackState.hasStableVideoBlock(
    currentMediaId: String?,
    nowMs: Long,
): Boolean =
    currentMediaId != null && mediaId == currentMediaId &&
        (phase == CapsuleVideoPhase.UNAVAILABLE || requestBlockedUntilMs > nowMs)

/** Repeated taps are idempotent only while work/presentation actually exists. */
internal val CapsuleVideoPlaybackState.videoRequestAlreadyActive: Boolean
    get() = preferredMode == CapsulePlaybackMode.VIDEO && phase in setOf(
        CapsuleVideoPhase.RESOLVING, CapsuleVideoPhase.PREPARING,
        CapsuleVideoPhase.RECOVERING, CapsuleVideoPhase.PLAYING, CapsuleVideoPhase.BUFFERING,
    )
