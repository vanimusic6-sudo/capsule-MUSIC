/*
 * Capsule MUSIC
 * YouTube audio/video mode state.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.video

enum class CapsulePlaybackMode {
    AUDIO,
    VIDEO,
}

enum class CapsuleVideoPhase {
    IDLE,
    RESOLVING,
    PLAYING,

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
    val message: String? = null,
)

const val CAPSULE_VIDEO_SCHEME = "capsule-video"
const val CAPSULE_VIDEO_CACHE_PREFIX = "capsule:video:"
const val CAPSULE_VIDEO_STREAM_CACHE_PREFIX = "capsule:video-stream:"
