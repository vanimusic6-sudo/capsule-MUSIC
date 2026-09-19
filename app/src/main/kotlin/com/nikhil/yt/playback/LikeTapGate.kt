package com.nikhil.yt.playback

/**
 * Accept every explicit like intent.
 *
 * Persistence is serialized by MusicService, so dropping rapid taps here makes the UI feel
 * unresponsive and changes the user's requested final state. The only invalid intent is a
 * missing media id.
 */
internal class LikeTapGate {
    fun accept(mediaId: String): Boolean = mediaId.isNotBlank()
}
