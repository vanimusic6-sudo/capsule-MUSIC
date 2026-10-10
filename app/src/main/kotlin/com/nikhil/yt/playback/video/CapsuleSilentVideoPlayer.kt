/*
 * Capsule MUSIC — video-only companion renderer.
 * The MediaLibrarySession audio player keeps running across VIDEO/AUDIO toggles.
 * Only the silent video decoder is prepared and stopped.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class CapsuleSilentVideoPlayer(
    context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val audioPlayer: ExoPlayer,
    private val scope: CoroutineScope,
    private val onError: (String, PlaybackException) -> Unit,
) {
    val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                volume = 0f
                setTrackSelectionParameters(
                    trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build(),
                )
            }

    private var activeMediaId: String? = null
    private var clockJob: Job? = null

    private val audioListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = sync(forceSeek = false)

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = sync(forceSeek = true)

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            sync(forceSeek = false)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                activeMediaId?.let { onError(it, error) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) sync(forceSeek = false)
            }
        })
    }

    fun start(mediaId: String, item: MediaItem) {
        stop()
        activeMediaId = mediaId
        audioPlayer.addListener(audioListener)
        player.setMediaItem(item)
        player.prepare()
        sync(forceSeek = true)
        clockJob = scope.launch {
            while (isActive && activeMediaId == mediaId) {
                delay(1_250L)
                sync(forceSeek = false)
            }
        }
    }

    fun stop() {
        clockJob?.cancel()
        clockJob = null
        if (activeMediaId != null) {
            activeMediaId = null
            audioPlayer.removeListener(audioListener)
        }
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    private fun sync(forceSeek: Boolean) {
        val mediaId = activeMediaId ?: return
        if (audioPlayer.currentMediaItem?.mediaId != mediaId) {
            stop()
            return
        }

        val position = audioPlayer.currentPosition.coerceAtLeast(0L)
        if (player.playbackState != Player.STATE_IDLE) {
            if (forceSeek || abs(player.currentPosition - position) > 650L) {
                player.seekTo(position)
            }
        }
        if (player.playbackParameters != audioPlayer.playbackParameters) {
            player.playbackParameters = audioPlayer.playbackParameters
        }
        player.playWhenReady = audioPlayer.isPlaying && audioPlayer.playbackState == Player.STATE_READY
    }

    fun release() {
        stop()
        player.release()
    }
}
