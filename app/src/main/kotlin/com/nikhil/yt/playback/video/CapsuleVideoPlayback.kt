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
    UNAVAILABLE,
}

/**
 * [preferredMode] is what the user chose and is sticky across queue transitions.
 * [mode] is what is actually playing right now. If a song has no matching clip,
 * preferredMode remains VIDEO while mode falls back to AUDIO and phase is UNAVAILABLE.
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
