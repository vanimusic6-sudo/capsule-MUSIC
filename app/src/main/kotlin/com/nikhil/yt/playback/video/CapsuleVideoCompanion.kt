/* Capsule MUSIC — isolated VIDEO session and sound ownership. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineStart
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
internal const val CAPSULE_VIDEO_SOUND_GAP_MS = 180L
internal const val CAPSULE_SOUND_RAMP_STEP_MS = 16L
internal const val CAPSULE_SOUND_RAMP_STEPS = 5

internal class CapsuleVideoCompanion(
    context: Context, factory: MediaSource.Factory, audio: ExoPlayer,
    private val scope: CoroutineScope, onSoundOwned: (Boolean) -> Unit,
    onReady: (String) -> Unit, onFailure: (String, Throwable) -> Unit,
    onFrameUnavailable: (String) -> Unit,
    onBufferingChanged: (String, Boolean) -> Unit,
) {
    private val loadControl = CapsuleVideoLoadControl()
    private var parkedExpiry: Job? = null
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(factory)
        .setLoadControl(loadControl)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    internal val frameRetention = CapsuleVideoFrameRetention(player, scope)
    private val session = CapsuleVideoSession(
        audio, player, scope, onSoundOwned, onReady, onFailure, onFrameUnavailable, onBufferingChanged,
    )
    fun start(id: String, item: MediaItem, volume: Float) {
        parkedExpiry?.cancel(); parkedExpiry = null
        loadControl.parked = false
        frameRetention.clear()
        session.start(id, item, volume)
    }
    fun setForeground(value: Boolean) = session.setForeground(value)
    fun setSurfaceAttached(value: Boolean) = session.setSurfaceAttached(value)
    fun setVolume(value: Float) = session.setVolume(value)
    fun stop() {
        frameRetention.retainExitFrame()
        val keep = player.playbackState == Player.STATE_READY &&
            player.duration > 0L && player.duration != C.TIME_UNSET
        loadControl.parked = keep
        session.stop(keepPrepared = keep)
        parkedExpiry?.cancel()
        parkedExpiry = if (keep) scope.launch {
            delay(45_000L)
            session.stop()
            loadControl.parked = false
            parkedExpiry = null
        } else null
    }
    fun release() {
        parkedExpiry?.cancel(); parkedExpiry = null
        frameRetention.release(); session.release(); player.release()
    }
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
    private val onBufferingChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    private var id: String? = null
    private var generation = 0L
    private var foreground = true
    private var surfaceAttached = false
    private var firstFrame = false
    private var frameReported = false
    private var ownsSound = false
    private var audible = false
    private var soundGain = 0f
    private var aligned = false
    private var committed = false
    private var reportedBuffering = false
    private var volume = 1f
    private var seamJob: Job? = null
    private var watchdog: Job? = null
    private var starting = false
    private var startedAtMs = 0L
    private var readyReported = false
    private val rendering get() = foreground && surfaceAttached
    private val hasAudio get() = video.currentTracks.groups.any {
        it.type == C.TRACK_TYPE_AUDIO && it.isSelected
    }
    private val ready get() = video.playbackState == Player.STATE_READY && hasAudio

    private val audioListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (id == null) return
            if (audio.currentMediaItem?.mediaId != id) { stop(); return }
            video.playWhenReady = audio.playWhenReady && (rendering || committed)
            video.playbackParameters = audio.playbackParameters
            evaluate()
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) {
            if (id == null || audio.currentMediaItem?.mediaId != id) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                firstFrame = false
                if (!committed) {
                    frameReported = false
                    onFrameUnavailable(id!!)
                    if (seamJob?.isActive == true) returnSound()
                }
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
            Timber.tag("CapsuleVideo").i("first-frame session=%s generation=%s prepareMs=%s", id, generation,
                SystemClock.elapsedRealtime() - startedAtMs)
            evaluate()
        }
        override fun onEvents(player: Player, events: Player.Events) = evaluate()
        override fun onPlayerError(error: PlaybackException) = fail(error)
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && id != null && !readyReported) {
                readyReported = true
                Timber.tag("CapsuleVideo").i("VIDEO timing ready session=%s prepareMs=%s", id,
                    SystemClock.elapsedRealtime() - startedAtMs)
            }
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
        // After the first presentation, VIDEO owns the session through seeks,
        // buffering, pauses and detached surfaces. Silence while loading is
        // part of VIDEO; playing MUSIC underneath it would change the mode.
        if (committed) {
            val buffering = video.playbackState == Player.STATE_BUFFERING
            if (reportedBuffering != buffering) {
                reportedBuffering = buffering
                onBufferingChanged(current, buffering)
                Timber.tag("CapsuleVideo").i("buffering=%s session=%s; VIDEO retained", buffering, current)
            }
            ensureWatchdog()
            return
        }
        // First align after preparation. Then correct drift introduced by that
        // seek once the current MUSIC position lies inside the buffered window.
        // This second correction consumes buffered samples, not another CDN seek.
        if (!ownsSound && rendering && ready && (!aligned || firstFrame)) {
            val initialAlignment = !aligned
            aligned = true
            val target = targetPosition()
            val drift = abs(video.currentPosition - target)
            val bufferedTarget = video.bufferedPosition >= target + 150L
            if (drift > 150L && (initialAlignment || bufferedTarget)) {
                Timber.tag("CapsuleVideo").i("VIDEO timing align session=%s deltaMs=%s", current, target - video.currentPosition)
                firstFrame = false
                frameReported = false
                onFrameUnavailable(current)
                video.seekTo(target)
                ensureWatchdog()
                return
            }
            // Keep MUSIC audible until the buffered correction can be made;
            // publishing a stale clip would visibly rewind the progress bar.
            if (drift > 150L) { ensureWatchdog(); return }
        }
        if (!audio.playWhenReady && rendering && firstFrame && ready && aligned) {
            // A fully prepared paused clip is already in VIDEO. It must resume
            // with its own soundtrack rather than replay the MUSIC handoff.
            seamJob?.cancel(); seamJob = null
            committed = true
            audible = true
            soundGain = 1f
            if (!ownsSound) { ownsSound = true; onSoundOwned(true) }
            video.volume = volume
            reportPresentation(current)
            ensureWatchdog()
            return
        }
        val canOwn = ready && video.isPlaying && audio.playWhenReady &&
            rendering && firstFrame && aligned
        if (canOwn && !ownsSound) {
            if (frameReported) {
                frameReported = false
                onFrameUnavailable(current)
            }
            ownsSound = true
            audible = false
            video.volume = 0f
            onSoundOwned(true)
            val lease = generation
            seamJob?.cancel()
            seamJob = scope.launch {
                // Keep the decoder hidden while both outputs are quiet. A
                // decoded frame alone must never reveal VIDEO over MUSIC.
                delay(CAPSULE_VIDEO_SOUND_GAP_MS)
                if (id == current && generation == lease && ownsSound && ready && video.isPlaying && audio.playWhenReady) {
                    audible = true
                    for (step in 1..CAPSULE_SOUND_RAMP_STEPS) {
                        if (id != current || generation != lease || !ownsSound || !ready ||
                            !video.isPlaying || !audio.playWhenReady) return@launch
                        soundGain = step.toFloat() / CAPSULE_SOUND_RAMP_STEPS
                        video.volume = volume * soundGain
                        delay(CAPSULE_SOUND_RAMP_STEP_MS)
                    }
                    committed = true
                    reportPresentation(current)
                    Timber.tag("CapsuleVideo").i("sound-owner VIDEO session=%s", current)
                } else {
                    returnSound()
                }
            }
        } else if (!canOwn && ownsSound) {
            returnSound()
        }
        ensureWatchdog()
    }

    private fun reportPresentation(current: String) {
        if (rendering && firstFrame && ready && !frameReported) {
            frameReported = true
            onReady(current)
        }
    }

    private fun returnSound() {
        seamJob?.cancel(); seamJob = null
        video.volume = 0f
        audible = false
        soundGain = 0f
        if (ownsSound) {
            if (!committed && audio.playWhenReady && frameReported) {
                frameReported = false
                id?.let(onFrameUnavailable)
            }
            ownsSound = false
            onSoundOwned(false)
        }
    }

    fun start(mediaId: String, item: MediaItem, outputVolume: Float) {
        val reuse = canReusePreparedVideo(video.currentMediaItem, item, video.playbackState)
        stop(keepPrepared = reuse)
        id = mediaId
        startedAtMs = SystemClock.elapsedRealtime()
        readyReported = false
        val lease = generation
        volume = outputVolume.coerceIn(0f, 1f)
        audio.addListener(audioListener)
        starting = true
        try {
            video.volume = 0f
            if (reuse) {
                if (abs(video.currentPosition - targetPosition()) > 150L) video.seekTo(targetPosition())
            } else {
                video.setMediaItem(item, targetPosition())
            }
            video.playbackParameters = audio.playbackParameters
            updateRendering()
            if (!reuse) video.prepare()
        } catch (failure: Exception) {
            starting = false
            fail(failure)
            return
        }
        starting = false
        Timber.tag("CapsuleVideo").i("prepare session=%s generation=%s reuse=%s; MUSIC audible", mediaId, lease, reuse)
        ensureWatchdog()
        evaluate()
    }

    private fun needsWatchdog(): Boolean = id != null && audio.playWhenReady &&
        ((rendering && !frameReported) ||
            ((rendering || committed) && video.playbackState == Player.STATE_BUFFERING))

    private fun ensureWatchdog() {
        if (!needsWatchdog()) {
            watchdog?.cancel(); watchdog = null
            return
        }
        if (watchdog?.isActive == true) return
        val currentId = id ?: return
        val lease = generation
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            var startupMs = 0L
            var stallMs = 0L
            while (id == currentId && generation == lease) {
                delay(500L)
                if (id != currentId || generation != lease) break
                if (!needsWatchdog()) break
                evaluate()
                // No first-frame timeout with a detached/minimized player.
                if (rendering && audio.playWhenReady && !frameReported) startupMs += 500L
                if (ready && frameReported) startupMs = 0L
                if ((rendering || committed) && audio.playWhenReady && video.playbackState == Player.STATE_BUFFERING) stallMs += 500L else stallMs = 0L
                if (startupMs >= 15_000L || stallMs >= 12_000L) {
                    fail(java.net.SocketTimeoutException("VIDEO readiness/stall timeout (surface=$surfaceAttached frame=$firstFrame audio=$hasAudio)"))
                    break
                }
            }
            if (generation == lease) watchdog = null
        }
        watchdog = launched
        launched.start()
    }

    private fun updateRendering() {
        // A surface transition is not a track transition. Disabling/re-enabling
        // VIDEO here reselected the muxed/HLS period and flushed its soundtrack.
        // Media3 handles absent output with its codec placeholder surface.
        // Keep the selected stream, timeline and sound owner unchanged.
        video.playWhenReady = audio.playWhenReady && (rendering || committed)
    }
    private fun changeRendering(change: () -> Unit) {
        val wasRendering = rendering
        change()
        if (wasRendering != rendering) {
            firstFrame = false
            if (!committed) {
                frameReported = false
                id?.let(onFrameUnavailable)
            }
            if (rendering && !committed) {
                aligned = false
                if (id != null && abs(video.currentPosition - targetPosition()) > 150L) video.seekTo(targetPosition())
            }
        }
        updateRendering()
        evaluate()
    }
    fun setForeground(value: Boolean) { if (foreground != value) changeRendering { foreground = value } }
    fun setSurfaceAttached(value: Boolean) { if (surfaceAttached != value) changeRendering { surfaceAttached = value } }
    fun setVolume(value: Float) { volume = value.coerceIn(0f, 1f); video.volume = if (ownsSound && audible) volume * soundGain else 0f }

    private fun fail(cause: Throwable) {
        val failed = id ?: return
        Timber.tag("CapsuleVideo").w(cause, "session-failure id=%s; MUSIC restored", failed)
        stop()
        onFailure(failed, cause)
    }
    fun stop(keepPrepared: Boolean = false) {
        generation++
        watchdog?.cancel(); watchdog = null
        val wasActive = id != null
        val returnPosition = video.currentPosition
        val syncMusic = committed && ownsSound && audio.currentMediaItem?.mediaId == id
        id = null
        if (wasActive) audio.removeListener(audioListener)
        if (syncMusic && abs(audio.currentPosition - returnPosition) > 150L) audio.seekTo(returnPosition)
        returnSound()
        firstFrame = false; frameReported = false; aligned = false; committed = false; reportedBuffering = false
        video.playWhenReady = false
        if (!keepPrepared) {
            video.stop()
            video.clearMediaItems()
        }
    }
    fun release() { stop(); video.removeListener(videoListener) }
}

internal fun canReusePreparedVideo(current: MediaItem?, requested: MediaItem, playbackState: Int): Boolean =
    current != null && current.mediaId == requested.mediaId &&
        current.localConfiguration == requested.localConfiguration && playbackState == Player.STATE_READY
