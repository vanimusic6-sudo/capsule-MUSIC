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
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
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

internal const val CAPSULE_VIDEO_SOUND_GAP_MS = 180L
internal const val CAPSULE_SOUND_RAMP_STEP_MS = 16L
internal const val CAPSULE_SOUND_RAMP_STEPS = 5

/**
 * Only an indexed DASH clip carries a reliable duration before Media3 prepare.
 * Start decoding around the advancing MUSIC clock instead of decoding at 0,
 * discarding that frame and performing two network seeks. Unknown lengths,
 * tiny clips and near-EOF switches retain the original safe start-at-zero path.
 */
internal fun capsuleDashVideoInitialPosition(
    item: MediaItem, audioPositionMs: Long, audioPlaying: Boolean,
): Long? {
    val resolved = item.localConfiguration?.tag as? YouTubeVideoResolver.ResolvedVideo ?: return null
    if (resolved.delivery != CapsuleVideoDelivery.DASH) return null
    val manifest = resolved.dashManifest?.takeIf { it.length in 1..(64 * 1024) } ?: return null
    val duration = manifest.substringAfter("mediaPresentationDuration=", "")
        .substringAfter("PT", "").substringBefore("S", "")
        .toDoubleOrNull()?.times(1000.0)?.toLong() ?: return null
    if (duration !in 10_000L..43_200_000L) return null
    val position = audioPositionMs.coerceAtLeast(0L)
    // The clip may be shorter than MUSIC; do not accidentally start beyond EOF.
    if (position >= duration - 3_000L) return null
    val prediction = if (audioPlaying) 1_400L else 0L
    return (position + prediction).coerceAtMost(duration - 3_000L)
}

internal class CapsuleVideoCompanion(
    context: Context, factory: MediaSource.Factory, audio: ExoPlayer,
    private val scope: CoroutineScope, onSoundOwned: (Boolean) -> Unit,
    onReady: (String) -> Unit, onFailure: (String, Throwable) -> Unit,
    onEnded: (String) -> Unit, onFrameUnavailable: (String) -> Unit,
    onBufferingChanged: (String, Boolean) -> Unit,
) {
    private var idleExpiry: Job? = null
    val player: ExoPlayer = ExoPlayer.Builder(context)
        // AUDIO downloads must not make HLS assume the VIDEO host is fast.
        .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).build())
        .setMediaSourceFactory(factory)
        // Let VIDEO adapt to actual VIDEO CDN throughput instead of forcing
        // the highest supported bitrate on slow first-byte connections.
        .setTrackSelector(DefaultTrackSelector(context))
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 10_000, 250, 1_200)
            .setPrioritizeTimeOverSizeThresholds(true).build())
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    internal val frameRetention = CapsuleVideoFrameRetention(player, scope)
    private val session = CapsuleVideoSession(
        audio, player, scope, onSoundOwned, onReady, onFailure, onEnded, onFrameUnavailable, onBufferingChanged,
    )
    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                if (session.awaitingPresentation) Timber.tag("CapsuleVideo").i(
                    "VIDEO startup load type=%s host=%s bytes=%s ms=%s", data.dataType, info.uri.host, info.bytesLoaded, info.loadDurationMs)
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (session.awaitingPresentation) Timber.tag("CapsuleVideo").i(
                    "VIDEO startup decoder=%s ms=%s", decoderName, initializationDurationMs)
            }
            override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) mediaLoadData.trackFormat?.let {
                    Timber.tag("CapsuleVideo").i("VIDEO format width=%s height=%s fps=%s bitrate=%s", it.width, it.height, it.frameRate, it.bitrate)
                }
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (droppedFrames > 5) Timber.tag("CapsuleVideo").i("VIDEO dropped frames=%s intervalMs=%s", droppedFrames, elapsedMs)
            }
        })
    }
    fun start(id: String, item: MediaItem, volume: Float) {
        idleExpiry?.cancel(); idleExpiry = null
        frameRetention.clear()
        val delivery = (item.localConfiguration?.tag as? YouTubeVideoResolver.ResolvedVideo)?.delivery
        session.start(id, item, volume, startupBudgetMs = if (delivery == CapsuleVideoDelivery.PROGRESSIVE) 8_000L else 15_000L)
    }
    fun setForeground(value: Boolean) = session.setForeground(value)
    // Read-only status for the toggle indicator; does not alter session playback.
    fun hasPreparedClip(mediaId: String): Boolean = session.hasPreparedClip(mediaId)
    fun canResume(item: MediaItem): Boolean = session.canResume(item)
    fun setSurfaceAttached(value: Boolean) = session.setSurfaceAttached(value)
    fun setVolume(value: Float) = session.setVolume(value)
    fun seekTo(positionMs: Long) = session.seekTo(positionMs)
    /** Natural VIDEO completion must never sync/rewind the canonical AUDIO clock. */
    fun stopForCompletedClip() {
        idleExpiry?.cancel(); idleExpiry = null
        frameRetention.retainExitFrame()
        session.stop(synchronizeMusic = false)
    }
    fun stop() {
        frameRetention.retainExitFrame()
        val keep = player.playbackState == Player.STATE_READY && player.duration > 0L
        session.stop(keepPrepared = keep)
        idleExpiry?.cancel()
        idleExpiry = if (keep) scope.launch {
            // One paused decoder, only for quick mode toggles. Standard Media3
            // buffering remains bounded to 12 seconds; no custom LoadControl.
            delay(20_000L)
            session.stop()
            idleExpiry = null
        } else null
    }
    fun release() {
        idleExpiry?.cancel(); idleExpiry = null
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
    private val onEnded: (String) -> Unit,
    private val onFrameUnavailable: (String) -> Unit,
    private val onBufferingChanged: (String, Boolean) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var id: String? = null
    private var generation = 0L
    private var foreground = true
    private var surfaceAttached = false
    // Physical frame lifetime differs from a session's presentation lifetime.
    // Media3 does not emit another first-frame event for a retained surface.
    private var preparedFrameAvailable = false
    private var firstFrame = false
    private var frameReported = false
    private var ownsSound = false
    private var audible = false
    private var soundGain = 0f
    private var aligned = false
    private var catchupPending = false
    private var seekStartedAtMs: Long? = null
    private var seekLatencyMs = 0L
    private var startupBudgetMs = 15_000L
    private var committed = false
    private var reportedBuffering = false
    private var volume = 1f
    private var seamJob: Job? = null
    private var watchdog: Job? = null
    private var starting = false
    private var synchronizingSeek = false
    private var deferredVideoSeekMs: Long? = null
    private var startedAtMs = 0L
    private var readyReported = false
    // The source factory replaces the synthetic URI with the CDN URI in the
    // published timeline. Compare original requests, not the rewritten item.
    private var preparedItem: MediaItem? = null
    fun hasPreparedClip(mediaId: String): Boolean =
        preparedItem?.mediaId == mediaId && video.playbackState == Player.STATE_READY
    fun canResume(item: MediaItem): Boolean =
        canReusePreparedVideo(preparedItem, item, video.playbackState)
    private val rendering get() = foreground && surfaceAttached
    private val hasAudio get() = video.currentTracks.groups.any {
        it.type == C.TRACK_TYPE_AUDIO && it.isSelected
    }
    private val ready get() = video.playbackState == Player.STATE_READY && hasAudio
    val awaitingPresentation get() = id != null && !committed

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
                if (synchronizingSeek) return
                if (capsuleVideoSeekCompletes(audio.currentPosition, video.duration)) {
                    finishClip()
                    return
                }
                firstFrame = false
                preparedFrameAvailable = false
                if (!committed) {
                    seekStartedAtMs = null
                    catchupPending = false
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
            seekStartedAtMs?.let {
                // A network seek can take far longer than a subsequent
                // buffered seek. Bound prediction to the handoff tolerance.
                seekLatencyMs = (clockMs() - it).coerceIn(0L, 400L)
                seekStartedAtMs = null
            }
            firstFrame = true
            preparedFrameAvailable = true
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
                // VIDEO end is a real track completion, never a recoverable network error.
                finishClip()
                return
            }
            evaluate()
        }
    }
    init { video.volume = 0f; video.addListener(videoListener) }

    private fun targetPosition(): Long =
        capsuleVideoStartupSeek(audio.currentPosition, video.duration)

    private fun startupSeekTarget(): Long {
        // A shorter clip requested while AUDIO is already past EOF starts at 0.
        // Prediction must not offset that explicit reset or a paused seek.
        val duration = capsuleKnownDuration(video.duration)
        val pastEnd = duration != null && audio.currentPosition >= duration
        val prediction = if (audio.isPlaying && !pastEnd) seekLatencyMs else 0L
        return capsuleClampDecoderSeek(targetPosition() + prediction, video.duration)
    }

    /**
     * MUSIC stays the canonical queue owner. If we entered a shorter VIDEO
     * from a MUSIC position already beyond VIDEO EOF, rewind ONLY the silent
     * shadow clock after acquiring VIDEO sound. Otherwise MUSIC would advance
     * the queue early while the newly restarted VIDEO is still playing.
     */
    private fun alignSilentAudioAfterClipRestart() {
        val clipDuration = capsuleKnownDuration(video.duration) ?: return
        if (audio.currentPosition < clipDuration) return
        val safeShadowPosition = capsuleClampDecoderSeek(video.currentPosition, audio.duration)
        synchronizingSeek = true
        try {
            audio.seekTo(safeShadowPosition)
        } finally {
            synchronizingSeek = false
        }
    }

    private fun finishClip() {
        val finishedId = id ?: return
        // Clear the lease before the callback can advance MUSIC's queue. This
        // makes ENDED, EOF seek and a concurrent next-track event idempotent.
        stop(synchronizeMusic = false)
        onEnded(finishedId)
    }

    /**
     * Service-owned VIDEO seek: update the canonical silent clock without
     * re-entering the audio discontinuity listener and overwriting the clip seek.
     */
    fun seekTo(positionMs: Long) {
        val currentId = id ?: return
        if (audio.currentMediaItem?.mediaId != currentId) { stop(synchronizeMusic = false); return }
        // Before the manifest/timeline establishes a real duration, an
        // arbitrary far seek can push some extractors past their last segment.
        // Store only the intent and clamp/apply once the duration is known.
        if (capsuleKnownDuration(video.duration) == null) {
            deferredVideoSeekMs = positionMs.coerceAtLeast(0L)
            return
        }
        if (capsuleVideoSeekCompletes(positionMs, video.duration)) { finishClip(); return }
        val target = capsuleClampDecoderSeek(positionMs, video.duration)
        if (!committed) {
            firstFrame = false
            preparedFrameAvailable = false
            frameReported = false
            seekStartedAtMs = null
            catchupPending = false
            onFrameUnavailable(currentId)
        }
        aligned = true
        video.seekTo(target)
        synchronizingSeek = true
        try {
            audio.seekTo(capsuleClampDecoderSeek(target, audio.duration))
        } finally {
            synchronizingSeek = false
        }
    }

    private fun seekForStartup(target: Long) {
        preparedFrameAvailable = false
        firstFrame = false
        frameReported = false
        seekStartedAtMs = clockMs()
        id?.let(onFrameUnavailable)
        video.seekTo(capsuleClampDecoderSeek(target, video.duration))
    }

    private fun evaluate() {
        val current = id ?: return
        if (starting) return
        if (audio.currentMediaItem?.mediaId != current) { stop(); return }
        val deferredSeek = deferredVideoSeekMs
        if (deferredSeek != null && capsuleKnownDuration(video.duration) != null) {
            deferredVideoSeekMs = null
            seekTo(deferredSeek)
            return
        }
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
        if (!ownsSound && !audio.playWhenReady && rendering && ready && firstFrame &&
            abs(video.currentPosition - targetPosition()) > 50L) {
            aligned = true
            catchupPending = false
            seekForStartup(targetPosition())
            return
        }
        // Music has advanced during extraction/buffering. Align at readiness,
        // before silencing MUSIC, then wait for the post-seek decoded frame.
        if (!ownsSound && !aligned && rendering && ready) {
            aligned = true
            val target = targetPosition()
            if (abs(video.currentPosition - target) > 400L) {
                Timber.tag("CapsuleVideo").i("VIDEO timing align session=%s deltaMs=%s", current, target - video.currentPosition)
                catchupPending = true
                seekForStartup(startupSeekTarget())
                ensureWatchdog()
                return
            }
        }
        // A slow first seek can finish behind MUSIC. Correct it once, only
        // inside the available buffer. Never chase MUSIC with a seek loop.
        if (!ownsSound && catchupPending && rendering && ready && firstFrame) {
            val target = startupSeekTarget()
            if (abs(video.currentPosition - targetPosition()) > 400L) {
                val bufferedTarget = capsuleClampDecoderSeek(target + 350L, video.duration)
                if (video.bufferedPosition < bufferedTarget) {
                    ensureWatchdog()
                    return
                }
                catchupPending = false
                Timber.tag("CapsuleVideo").i("VIDEO startup buffered correction session=%s deltaMs=%s", current, target - video.currentPosition)
                seekForStartup(target)
                ensureWatchdog()
                return
            }
            catchupPending = false
        }
        if (!audio.playWhenReady && rendering && firstFrame && ready && aligned) {
            // A fully prepared paused clip is already in VIDEO. It must resume
            // with its own soundtrack rather than replay the MUSIC handoff.
            seamJob?.cancel(); seamJob = null
            committed = true
            audible = true
            soundGain = 1f
            if (!ownsSound) {
                ownsSound = true
                onSoundOwned(true)
                alignSilentAudioAfterClipRestart()
            }
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
            alignSilentAudioAfterClipRestart()
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
            Timber.tag("CapsuleVideo").i("VIDEO startup handoff session=%s musicMs=%s videoMs=%s", current, audio.currentPosition, video.currentPosition)
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

    fun start(mediaId: String, item: MediaItem, outputVolume: Float, startupBudgetMs: Long = 15_000L) {
        val reuse = video.currentMediaItem?.mediaId == mediaId &&
            canReusePreparedVideo(preparedItem, item, video.playbackState)
        stop(keepPrepared = reuse)
        firstFrame = reuse && rendering && preparedFrameAvailable
        this.startupBudgetMs = startupBudgetMs
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
                if (abs(video.currentPosition - targetPosition()) > 400L) {
                    // This seek already aligns the reopened clip. READY must
                    // not schedule the same alignment a second time.
                    aligned = true
                    catchupPending = true
                    seekForStartup(startupSeekTarget())
                }
            } else {
                preparedItem = item
                // Indexed DASH carries duration already: start decoder near the
                // advancing MUSIC clock, not at byte/frame zero. Media3 gets the
                // initial position before prepare, skipping a redundant network seek.
                // Unindexed/short/near-EOF clips keep the proven old path.
                val initialPosition = capsuleDashVideoInitialPosition(
                    item, audio.currentPosition, audio.isPlaying,
                )
                if (initialPosition != null) {
                    video.setMediaItem(item, initialPosition)
                    Timber.tag("CapsuleVideo").i(
                        "VIDEO start-near-audio session=%s positionMs=%s musicMs=%s",
                        mediaId, initialPosition, audio.currentPosition,
                    )
                } else {
                    video.setMediaItem(item)
                }
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
                if (startupMs >= startupBudgetMs || stallMs >= 12_000L) {
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
            preparedFrameAvailable = false
            firstFrame = false
            if (!committed) {
                frameReported = false
                id?.let(onFrameUnavailable)
            }
            if (rendering && !committed) {
                aligned = false
                if (id != null && video.playbackState == Player.STATE_READY && abs(video.currentPosition - targetPosition()) > 400L) {
                    aligned = true
                    catchupPending = true
                    seekForStartup(startupSeekTarget())
                }
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
        stop(synchronizeMusic = false)
        onFailure(failed, cause)
    }
    fun stop(keepPrepared: Boolean = false, synchronizeMusic: Boolean = true) {
        generation++
        watchdog?.cancel(); watchdog = null
        val wasActive = id != null
        val returnPosition = video.currentPosition
        // A failed/stalled clip has no authority to rewind healthy MUSIC.
        val syncMusic = synchronizeMusic && committed && ownsSound && audio.currentMediaItem?.mediaId == id
        id = null
        deferredVideoSeekMs = null
        if (wasActive) audio.removeListener(audioListener)
        if (syncMusic) {
            val safePosition = capsuleClampDecoderSeek(returnPosition, audio.duration)
            if (abs(audio.currentPosition - safePosition) > 100L) audio.seekTo(safePosition)
        }
        returnSound()
        firstFrame = false; frameReported = false; aligned = false; catchupPending = false; seekStartedAtMs = null; committed = false; reportedBuffering = false
        video.playWhenReady = false
        if (!keepPrepared) {
            preparedFrameAvailable = false
            preparedItem = null
            video.stop()
            video.clearMediaItems()
        }
    }
    fun release() { stop(); video.removeListener(videoListener) }
}

internal fun canReusePreparedVideo(current: MediaItem?, requested: MediaItem, playbackState: Int): Boolean =
    current != null && current.mediaId == requested.mediaId &&
        current.localConfiguration == requested.localConfiguration && playbackState == Player.STATE_READY
