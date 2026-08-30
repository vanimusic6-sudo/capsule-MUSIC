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

data class CapsuleVideoPlaybackState(
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
