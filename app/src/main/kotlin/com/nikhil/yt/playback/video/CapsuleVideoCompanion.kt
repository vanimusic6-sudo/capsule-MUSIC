/*
 * Capsule MUSIC — prepare VIDEO without cutting off the current AUDIO song.
 * GPL-3.0
 *
 * Separate Media3 decoder, same MediaSourceFactory as the stable VIDEO route.
 * This object does not replace a MediaSession item or touch AUDIO extraction.
 * The primary AUDIO queue remains audible until the VIDEO renderer has both
 * its first real frame and a selected original clip soundtrack.
 */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

internal fun canSwitchToVideoSound(
    firstFrameRendered: Boolean,
    ready: Boolean,
    originalAudioSelected: Boolean,
    musicIsPlaying: Boolean,
    clipIsPlaying: Boolean,
): Boolean = firstFrameRendered && ready && originalAudioSelected &&
    musicIsPlaying && clipIsPlaying

internal class CapsuleVideoCompanion(
    context: Context,
    factory: MediaSource.Factory,
    private val audio: ExoPlayer,
    private val scope: CoroutineScope,
    private val onSoundOwned: (Boolean) -> Unit,
    private val onReady: (String) -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(factory)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build().apply { volume = 0f }

    private var mediaId: String? = null
    private var firstFrame = false
    private var ownsSound = false
    private var clipAudible = false
    private var readyReported = false
    private var switchGapJob: Job? = null
    private var requestedVolume = 1f
    private var startupTimeout: Job? = null
    private var isFailed = false
    private var foreground = true

    private fun hasAudio(): Boolean =
        player.currentTracks.groups.any {
            it.type == C.TRACK_TYPE_AUDIO && it.isSelected
        }

    private fun readyForSound(): Boolean =
        foreground && firstFrame && player.playbackState == Player.STATE_READY && hasAudio()

    private fun evaluate() {
        val current = mediaId ?: return
        if (isFailed) return
        if (audio.currentMediaItem?.mediaId != current) {
            stop()
            return
        }
        val ready = readyForSound()
        val handoff = canSwitchToVideoSound(
            firstFrameRendered = firstFrame,
            ready = player.playbackState == Player.STATE_READY,
            originalAudioSelected = hasAudio(),
            // isPlaying may briefly turn false during AUDIO CDN buffering.
            // VIDEO is an independent renderer; follow user intent, not the
            // audio decoder's transient buffering/ready state.
            musicIsPlaying = audio.playWhenReady,
            clipIsPlaying = foreground && player.isPlaying,
        )
        if (handoff != ownsSound) {
            switchGapJob?.cancel()
            switchGapJob = null
            ownsSound = handoff
            clipAudible = false
            Timber.tag("CapsuleVideo").i(
                "sound-handoff clip=%s destination=%s",
                current, if (handoff) "VIDEO" else "AUDIO",
            )
            if (handoff) {
                // The listener is already playing the VIDEO muted. Briefly
                // silence BOTH outputs between the two different mixes,
                // rather than abruptly switching waves at the same instant.
                // AUDIO must be restored immediately if VIDEO stalls or stops.
                player.volume = 0f
                onSoundOwned(true)
                switchGapJob = scope.launch {
                    delay(115L)
                    if (mediaId == current && ownsSound && foreground && readyForSound() &&
                        player.isPlaying && audio.playWhenReady
                    ) {
                        clipAudible = true
                        player.volume = requestedVolume
                    }
                }
            } else {
                player.volume = 0f
                onSoundOwned(false)
            }
        }
        if (ready && !readyReported) {
            readyReported = true
            startupTimeout?.cancel()
            startupTimeout = null
            onReady(current)
        }
    }

    private val mainListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A main AUDIO CDN stall does not mean the user pressed Pause.
            player.playWhenReady = foreground && audio.playWhenReady
            evaluate()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            player.playWhenReady = foreground && playWhenReady
            evaluate()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (audio.currentMediaItem?.mediaId != mediaId) {
                stop()
            } else if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                if (player.playbackState != Player.STATE_IDLE) {
                    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    val wanted = audio.currentPosition.coerceAtLeast(0)
                    player.seekTo(duration?.let { wanted.coerceAtMost(it - 1) } ?: wanted)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem?.mediaId != mediaId) stop()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            player.playbackParameters = playbackParameters
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Timber.tag("CapsuleVideo").i("first-frame clip=%s", mediaId)
                firstFrame = true
                evaluate()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                evaluate()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                evaluate()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Media3 may publish STATE_READY before its selected
                // audio-track callback. Don't reject a playable clip due to
                // that observer ordering race. The 12-second foreground
                // readiness watchdog detects genuinely missing audio.
                if (playbackState == Player.STATE_ENDED) {
                    mediaId?.let { fail(it, IllegalStateException("VIDEO clip ended")) }
                    return
                }
                evaluate()
            }

            override fun onPlayerError(error: PlaybackException) {
                mediaId?.let { fail(it, error) }
            }
        })
    }

    private fun fail(id: String, cause: Throwable) {
        if (isFailed || mediaId != id) return
        isFailed = true
        Timber.tag("CapsuleVideo").w(cause, "video-failure clip=%s; AUDIO restored", id)
        stop()
        onFailure(id, cause)
    }

    fun start(id: String, item: MediaItem, outputVolume: Float) {
        stop()
        mediaId = id
        firstFrame = false
        isFailed = false
        readyReported = false
        clipAudible = false
        requestedVolume = outputVolume.coerceIn(0f, 1f)
        audio.addListener(mainListener)
        player.volume = 0f
        Timber.tag("CapsuleVideo").i("prepare clip=%s; AUDIO stays running", id)
        player.setMediaItem(item)
        player.seekTo(audio.currentPosition.coerceAtLeast(0L))
        player.playbackParameters = audio.playbackParameters
        player.playWhenReady = foreground && audio.playWhenReady
        player.prepare()
        startupTimeout = scope.launch {
            // A decoder may render a first frame with no selected soundtrack.
            // That is NOT readiness. Only count active playback time, so users
            // who enter VIDEO while paused are never timed out while idle.
            var activeWaitMs = 0L
            while (mediaId == id && !readyForSound()) {
                delay(1_000L)
                if (mediaId != id || readyForSound()) break
                if (foreground && audio.playWhenReady) activeWaitMs += 1_000L
                if (activeWaitMs >= 12_000L) {
                    fail(id, IllegalStateException(
                        "VIDEO frame/soundtrack readiness timed out; AUDIO preserved",
                    ))
                    break
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            // Preserve VIDEO selection in the background, but do not render
            // with a detached UI surface. Main AUDIO plays in the background.
            player.playWhenReady = false
            firstFrame = false
            evaluate()
        } else if (mediaId != null) {
            firstFrame = false
            readyReported = false
            val wanted = audio.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            player.seekTo(duration?.let { wanted.coerceAtMost(it - 1) } ?: wanted)
            player.playWhenReady = foreground && audio.playWhenReady
            evaluate()
        }
    }

    fun setVolume(value: Float) {
        requestedVolume = value.coerceIn(0f, 1f)
        player.volume = if (ownsSound && clipAudible) requestedVolume else 0f
    }

    fun stop() {
        switchGapJob?.cancel()
        switchGapJob = null
        clipAudible = false
        readyReported = false
        startupTimeout?.cancel()
        startupTimeout = null
        val hadId = mediaId != null
        mediaId = null
        if (hadId) audio.removeListener(mainListener)
        if (ownsSound) {
            player.volume = 0f
            ownsSound = false
            onSoundOwned(false)
        }
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        firstFrame = false
    }

    fun release() {
        stop()
        player.release()
    }
}
