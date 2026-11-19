/*
 * Capsule MUSIC — process-retained Canvas transport.
 * The visual renderer can unmount while this muted Media3 timeline keeps looping.
 * No lookup is performed by this object. A new song is discovered only from an
 * opened Immersion player; closing the sheet never prepares a new track.
 * GPL-3.0.
 */
@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.ui.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nikhil.yt.canvas.CapsuleMotionClip
import com.nikhil.yt.canvas.CapsuleMotionSource

/**
 * One muted player for the application, not one player per Composable.
 * A TextureView can be detached/rebound without seeking to zero, while the
 * audio stream and its main MusicService are completely independent.
 * Call release() on Activity finish or when the feature is turned OFF.
 *
 * Main-thread ownership: ExoPlayer and Compose state must never be touched
 * from a network or bitmap extraction dispatcher.
 */
internal object CapsuleMotionPlaybackSession {
    var mediaId by mutableStateOf<String?>(null)
        private set
    var clip by mutableStateOf<CapsuleMotionClip?>(null)
        private set
    var ambientFrame by mutableStateOf<CapsuleVideoAmbientFrame?>(null)
        private set
    var retainedFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var appleFailed by mutableStateOf(false)
        private set
    var permanentFailure by mutableStateOf(false)
        private set

    var player: ExoPlayer? = null
        private set

    private var listener: Player.Listener? = null

    fun hasPrepared(mediaId: String): Boolean =
        this.mediaId == mediaId && player != null && clip != null

    fun isCurrent(mediaId: String): Boolean = this.mediaId == mediaId

    fun begin(mediaId: String) {
        if (this.mediaId == mediaId) return
        releasePlayer()
        this.mediaId = mediaId
        clip = null
        ambientFrame = null
        retainedFrame = null
        appleFailed = false
        permanentFailure = false
    }

    fun play(context: Context, mediaId: String, next: CapsuleMotionClip) {
        if (this.mediaId != mediaId || permanentFailure) return
        if (clip == next && player != null) return
        releasePlayer()
        ambientFrame = null
        retainedFrame = null
        clip = next
        val exo = ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(720, 1280)
                .setMaxVideoBitrate(2_500_000)
                .build()
            setMediaItem(
                MediaItem.Builder().setUri(next.uri).setMimeType(
                    if (next.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
                ).build(),
            )
        }
        val registration = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo || this@CapsuleMotionPlaybackSession.mediaId != mediaId) return
                if (next.source == CapsuleMotionSource.APPLE_ARTWORK && !appleFailed) {
                    appleFailed = true
                } else {
                    permanentFailure = true
                }
                releasePlayer()
            }
        }
        player = exo
        listener = registration
        exo.addListener(registration)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun onFrame(frame: CapsuleVideoAmbientFrame) {
        if (frame.mediaId != mediaId || player == null) return
        val previous = ambientFrame
        if (previous == null ||
            capsuleFieldChanged(previous.field, frame.field, threshold = 0.0045f)
        ) {
            ambientFrame = frame
        }
    }

    fun retain(bitmap: Bitmap) {
        if (player == null) { bitmap.recycle(); return }
        retainedFrame = bitmap.asImageBitmap()
    }

    private fun releasePlayer() {
        val old = player
        player = null
        val registered = listener
        listener = null
        if (old != null) {
            if (registered != null) old.removeListener(registered)
            old.playWhenReady = false
            old.clearVideoSurface()
            old.stop()
            old.release()
        }
    }

    fun release() {
        releasePlayer()
        clip = null
        mediaId = null
        ambientFrame = null
        retainedFrame = null
        appleFailed = false
        permanentFailure = false
    }
}
