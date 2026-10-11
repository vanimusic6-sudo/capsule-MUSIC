/* Capsule MUSIC — isolated VIDEO session and sound ownership. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

internal fun canSwitchToVideoSound(
    firstFrameRendered: Boolean, ready: Boolean, originalAudioSelected: Boolean,
    musicIsPlaying: Boolean, clipIsPlaying: Boolean,
): Boolean = firstFrameRendered && ready && originalAudioSelected && musicIsPlaying && clipIsPlaying

internal class CapsuleVideoEndedException : IllegalStateException("VIDEO clip ended")

internal class CapsuleVideoCompanion(
    context: Context, factory: MediaSource.Factory, audio: ExoPlayer,
    scope: CoroutineScope, onSoundOwned: (Boolean) -> Unit,
    onReady: (String) -> Unit, onFailure: (String, Throwable) -> Unit,
    onFrameUnavailable: (String) -> Unit,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(factory)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 12_000, 350, 750)
            .setPrioritizeTimeOverSizeThresholds(true).build())
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    private val session = CapsuleVideoSession(
        audio, player, scope, onSoundOwned, onReady, onFailure, onFrameUnavailable,
    )
    fun start(id: String, item: MediaItem, volume: Float) = session.start(id, item, volume)
    fun setForeground(value: Boolean) = session.setForeground(value)
    fun setSurfaceAttached(value: Boolean) = session.setSurfaceAttached(value)
    fun setVolume(value: Float) = session.setVolume(value)
    fun stop() = session.stop()
    fun release() { session.release(); player.release() }
}

/**
 * One owner for decoder readiness, surface lifetime, timeline alignment and
 * sound. Callbacks do not depend on Media3's READY/tracks/frame event order.
 * Every asynchronous completion is leased to a session generation.
 */
internal class CapsuleVideoSession(
    private val audio: Player,
    private val video: Player,
    private val scope: CoroutineScope,
    private val onSoundOwned: (Boolean) -> Unit,
    private val onReady: (String) -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
    private val onFrameUnavailable: (String) -> Unit,
) {
    private var id: String? = null
    private var generation = 0L
    private var foreground = true
    private var surfaceAttached = false
    private var firstFrame = false
    private var frameReported = false
    private var ownsSound = false
    private var audible = false
    private var aligned = false
    private var committed = false
    private var volume = 1f
    private var seamJob: Job? = null
    private var watchdog: Job? = null
    private var starting = false
    private val rendering get() = foreground && surfaceAttached
    private val hasAudio get() = video.currentTracks.groups.any {
        it.type == C.TRACK_TYPE_AUDIO && it.isSelected
    }
    private val ready get() = video.playbackState == Player.STATE_READY && hasAudio

    private val audioListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (id == null) return
            if (audio.currentMediaItem?.mediaId != id) { stop(); return }
            video.playWhenReady = audio.playWhenReady && (rendering || ownsSound)
            video.playbackParameters = audio.playbackParameters
            evaluate()
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (id == null || audio.currentMediaItem?.mediaId != id) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                firstFrame = false
                frameReported = false
                onFrameUnavailable(id!!)
                aligned = true
                video.seekTo(targetPosition())
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (id != null && mediaItem?.mediaId != id) stop()
        }
    }
    private val videoListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (id == null || !rendering) return
            firstFrame = true
            Timber.tag("CapsuleVideo").i("first-frame session=%s generation=%s", id, generation)
            evaluate()
        }
        override fun onEvents(player: Player, events: Player.Events) = evaluate()
        override fun onPlayerError(error: PlaybackException) = fail(error)
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && id != null) {
                // A clip can be shorter than the album track. Restore MUSIC
                // once; an ordinary end is not a CDN failure to retry.
                fail(CapsuleVideoEndedException())
                return
            }
            evaluate()
        }
    }
    init { video.volume = 0f; video.addListener(videoListener) }

    private fun targetPosition(): Long {
        val position = audio.currentPosition.coerceAtLeast(0L)
        val duration = video.duration
        return if (duration > 0 && duration != C.TIME_UNSET) position.coerceAtMost((duration - 1).coerceAtLeast(0L)) else position
    }

    private fun evaluate() {
        val current = id ?: return
        if (starting) return
        if (audio.currentMediaItem?.mediaId != current) { stop(); return }
        // Music has advanced during extraction/buffering. Align at readiness,
        // before silencing MUSIC, then wait for the post-seek decoded frame.
        if (!aligned && rendering && ready && firstFrame) {
            aligned = true
            val target = targetPosition()
            if (abs(video.currentPosition - target) > 400L) {
                firstFrame = false
                frameReported = false
                onFrameUnavailable(current)
                video.seekTo(target)
                return
            }
        }
        if (rendering && firstFrame && ready && !frameReported) {
            frameReported = true
            onReady(current)
        }
        val canOwn = ready && video.isPlaying && audio.playWhenReady &&
            (committed || (rendering && firstFrame && aligned))
        if (canOwn && !ownsSound) {
            ownsSound = true
            audible = false
            video.volume = 0f
            onSoundOwned(true)
            val lease = generation
            seamJob?.cancel()
            seamJob = scope.launch {
                delay(115L)
                if (id == current && generation == lease && ownsSound && ready && video.isPlaying && audio.playWhenReady) {
                    audible = true
                    committed = true
                    video.volume = volume
                    Timber.tag("CapsuleVideo").i("sound-owner VIDEO session=%s", current)
                } else {
                    returnSound()
                }
            }
        } else if (!canOwn && ownsSound) {
            returnSound()
        }
    }

    private fun returnSound() {
        seamJob?.cancel(); seamJob = null
        video.volume = 0f
        audible = false
        if (ownsSound) { ownsSound = false; onSoundOwned(false) }
    }

    fun start(mediaId: String, item: MediaItem, outputVolume: Float) {
        stop()
        id = mediaId
        val lease = generation
        volume = outputVolume.coerceIn(0f, 1f)
        audio.addListener(audioListener)
        starting = true
        try {
            video.volume = 0f
            video.setMediaItem(item, targetPosition())
            video.playbackParameters = audio.playbackParameters
            updateRendering()
            video.prepare()
        } catch (failure: Exception) {
            starting = false
            fail(failure)
            return
        }
        starting = false
        Timber.tag("CapsuleVideo").i("prepare session=%s generation=%s; MUSIC audible", mediaId, lease)
        watchdog = scope.launch {
            var startupMs = 0L
            var stallMs = 0L
            while (id == mediaId && generation == lease) {
                delay(500L)
                if (id != mediaId || generation != lease) break
                evaluate()
                // No first-frame timeout with a detached/minimized player.
                if (rendering && audio.playWhenReady && !frameReported) startupMs += 500L
                if (ready && frameReported) startupMs = 0L
                if ((rendering || ownsSound) && audio.playWhenReady && video.playbackState == Player.STATE_BUFFERING) stallMs += 500L else stallMs = 0L
                if (startupMs >= 15_000L || stallMs >= 12_000L) {
                    fail(java.net.SocketTimeoutException("VIDEO readiness/stall timeout (surface=$surfaceAttached frame=$firstFrame audio=$hasAudio)"))
                    break
                }
            }
        }
        evaluate()
    }

    private fun updateRendering() {
        // Keep the original clip soundtrack in background. Disable VIDEO
        // decoding while its surface is absent, rather than changing mode or
        // silently switching to the album's different master/mix.
        video.trackSelectionParameters = video.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !rendering).build()
        video.playWhenReady = audio.playWhenReady && (rendering || ownsSound)
    }
    private fun changeRendering(change: () -> Unit) {
        val wasRendering = rendering
        change()
        if (wasRendering != rendering) {
            firstFrame = false
            frameReported = false
            id?.let(onFrameUnavailable)
            if (rendering && !ownsSound) {
                aligned = false
                if (id != null) video.seekTo(targetPosition())
            }
        }
        updateRendering()
        evaluate()
    }
    fun setForeground(value: Boolean) { if (foreground != value) changeRendering { foreground = value } }
    fun setSurfaceAttached(value: Boolean) { if (surfaceAttached != value) changeRendering { surfaceAttached = value } }
    fun setVolume(value: Float) { volume = value.coerceIn(0f, 1f); video.volume = if (ownsSound && audible) volume else 0f }

    private fun fail(cause: Throwable) {
        val failed = id ?: return
        Timber.tag("CapsuleVideo").w(cause, "session-failure id=%s; MUSIC restored", failed)
        stop()
        onFailure(failed, cause)
    }
    fun stop() {
        generation++
        watchdog?.cancel(); watchdog = null
        val wasActive = id != null
        id = null
        if (wasActive) audio.removeListener(audioListener)
        returnSound()
        firstFrame = false; frameReported = false; aligned = false; committed = false
        video.playWhenReady = false
        video.stop()
        video.clearMediaItems()
    }
    fun release() { stop(); video.removeListener(videoListener) }
}
