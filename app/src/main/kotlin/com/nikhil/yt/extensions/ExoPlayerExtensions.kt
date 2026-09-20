@file:Suppress("UnsafeOptInUsageError")

/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.extensions

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import com.nikhil.yt.App
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private data class CapsuleOffloadDiagnostics(
    val audioOffloadListener: ExoPlayer.AudioOffloadListener,
    val playerListener: Player.Listener,
    val analyticsListener: AnalyticsListener,
)

private val capsuleOffloadDiagnostics =
    WeakHashMap<ExoPlayer, CapsuleOffloadDiagnostics>()

private val capsuleOffloadSupportProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    DefaultAudioOffloadSupportProvider(App.instance.applicationContext)
}

internal enum class CapsuleAudioOffloadAvailability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

internal fun ExoPlayer.isAudioOffloadRequested(): Boolean =
    trackSelectionParameters.audioOffloadPreferences.audioOffloadMode !=
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED

/**
 * Returns platform capability for the currently selected audio format and
 * current audio route. This is deliberately tri-state: before Media3 has a
 * selected audio track, Capsule must not pretend that support is known.
 *
 * SUPPORTED means the platform reports this format/route as offload-capable;
 * actual renderer engagement is still confirmed by onOffloadedPlayback(true).
 */
internal fun ExoPlayer.currentAudioOffloadAvailability(): CapsuleAudioOffloadAvailability {
    val selectedFormats =
        currentTracks.groups.flatMap { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) {
                emptyList()
            } else {
                (0 until group.length)
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
        }

    if (selectedFormats.size != 1) return CapsuleAudioOffloadAvailability.UNKNOWN

    val support =
        runCatching {
            capsuleOffloadSupportProvider.getAudioOffloadSupport(
                selectedFormats.single(),
                audioAttributes,
            )
        }.getOrNull() ?: return CapsuleAudioOffloadAvailability.UNKNOWN

    return if (support.isFormatSupported) {
        CapsuleAudioOffloadAvailability.SUPPORTED
    } else {
        CapsuleAudioOffloadAvailability.UNSUPPORTED
    }
}

private fun ExoPlayer.bufferedAheadMs(): Long =
    (bufferedPosition - currentPosition).coerceAtLeast(0L)

private fun ExoPlayer.logPlaybackHealth(prefix: String) {
    if (!GlobalLog.isEnabled) return
    Timber.tag("PlaybackHealth").w(
        "%s id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d isLoading=%s playWhenReady=%s state=%d",
        prefix,
        currentMediaItem?.mediaId,
        currentPosition,
        bufferedAheadMs(),
        totalBufferedDuration,
        isLoading,
        playWhenReady,
        playbackState,
    )
}

private fun ExoPlayer.logSelectedAudioOffloadCapability(trigger: String) {
    val preferences = trackSelectionParameters.audioOffloadPreferences
    val selectedFormats =
        currentTracks.groups.flatMap { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) {
                emptyList()
            } else {
                (0 until group.length)
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
        }

    if (selectedFormats.isEmpty()) {
        Timber.tag("AudioOffload").i(
            "capability trigger=%s selectedAudio=none preferenceMode=%d speed=%.3f",
            trigger,
            preferences.audioOffloadMode,
            playbackParameters.speed,
        )
        return
    }

    selectedFormats.forEachIndexed { index, format ->
        val support =
            runCatching {
                capsuleOffloadSupportProvider.getAudioOffloadSupport(format, audioAttributes)
            }.getOrElse { error ->
                Timber.tag("AudioOffload").w(
                    error,
                    "capability query failed trigger=%s mime=%s codecs=%s sampleRate=%d channels=%d",
                    trigger,
                    format.sampleMimeType,
                    format.codecs,
                    format.sampleRate,
                    format.channelCount,
                )
                return@forEachIndexed
            }

        val knownEligible =
            preferences.audioOffloadMode !=
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED &&
                selectedFormats.size == 1 &&
                support.isFormatSupported &&
                (!preferences.isGaplessSupportRequired || support.isGaplessSupported) &&
                (!preferences.isSpeedChangeSupportRequired || support.isSpeedChangeSupported)

        val reason =
            when {
                preferences.audioOffloadMode ==
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED ->
                    "preference-disabled"
                selectedFormats.size != 1 -> "multiple-selected-audio-tracks"
                !support.isFormatSupported -> "platform-format-unsupported"
                preferences.isGaplessSupportRequired && !support.isGaplessSupported ->
                    "gapless-required-unsupported"
                preferences.isSpeedChangeSupportRequired && !support.isSpeedChangeSupported ->
                    "speed-change-required-unsupported"
                else -> "platform-and-policy-eligible-awaiting-renderer"
            }

        Timber.tag("AudioOffload").i(
            "capability trigger=%s track=%d/%d mime=%s codecs=%s sampleRate=%d channels=%d bitrate=%d " +
                "formatSupported=%s gaplessSupported=%s speedChangeSupported=%s preferenceMode=%d " +
                "gaplessRequired=%s speedChangeRequired=%s speed=%.3f knownEligible=%s reason=%s",
            trigger,
            index + 1,
            selectedFormats.size,
            format.sampleMimeType,
            format.codecs,
            format.sampleRate,
            format.channelCount,
            format.bitrate,
            support.isFormatSupported,
            support.isGaplessSupported,
            support.isSpeedChangeSupported,
            preferences.audioOffloadMode,
            preferences.isGaplessSupportRequired,
            preferences.isSpeedChangeSupportRequired,
            playbackParameters.speed,
            knownEligible,
            reason,
        )
    }
}

/**
 * A little slack, so a report that races the resume it belongs to is still read as one.
 */
private const val AUDIO_RESUME_SLACK_MS = 250L

/**
 * Whether an underrun report is the sink being picked up again rather than audio breaking.
 *
 * Media3 raises onAudioUnderrun from the audio sink and hands it the time since the sink was last
 * fed. Nothing feeds a sink that is not playing, so a pause leaves that clock running and the
 * first report after the resume carries the whole idle stretch. A capture of forty-two minutes
 * held eleven reports, and the gaps they named were 2.6 s, 4.2 s, 12.8, 14.8, 16.6, 28.7, 47.0,
 * 126.9, 382.4 and 1285.9 — twenty-one minutes of "underrun" that nobody could have heard.
 *
 * Rather than guess a threshold, compare the gap with how long playback has actually been running.
 * A gap that reaches back past the moment playback started did not happen during playback. One
 * that fits inside it did, and that is worth an error: on the two real ones the sink went dry for
 * 2.6 and 4.2 seconds while three and a half minutes of audio sat decoded and waiting.
 *
 * With no known start — a report before playback was ever seen to begin — there is nothing to
 * compare against, and it is treated as an artefact rather than raised as a fault on no evidence.
 */
internal fun isAudioResumeArtefact(
    elapsedSinceLastFeedMs: Long,
    playingForMs: Long?,
): Boolean {
    if (elapsedSinceLastFeedMs <= 0L) return true
    val playingFor = playingForMs ?: return true
    return elapsedSinceLastFeedMs > playingFor + AUDIO_RESUME_SLACK_MS
}

private fun ExoPlayer.ensureCapsuleOffloadDiagnostics(): Boolean {
    synchronized(capsuleOffloadDiagnostics) {
        if (capsuleOffloadDiagnostics.containsKey(this)) return false

        val playerReference = WeakReference(this)
        val audioOffloadListener =
            object : ExoPlayer.AudioOffloadListener {
                override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
                    Timber.tag("AudioOffload").i(
                        "offloadedPlayback=%s",
                        isOffloadedPlayback,
                    )
                }

                override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
                    Timber.tag("AudioOffload").i(
                        "sleepingForOffload=%s",
                        isSleepingForOffload,
                    )
                }
            }

        val bufferingTracker = PlaybackBufferingTracker(SystemClock::elapsedRealtime)
        var discontinuityKind = "discontinuity"
        var playingSinceElapsedMs: Long? = null
        val playerListener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    playerReference.get()?.logSelectedAudioOffloadCapability("tracksChanged")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playingSinceElapsedMs =
                        if (isPlaying) SystemClock.elapsedRealtime() else null
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    discontinuityKind = if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) "seek" else "discontinuity"
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (!GlobalLog.isEnabled) {
                        bufferingTracker.reset()
                        return
                    }
                    // Observe the final state of the event batch, after selection and state callbacks.
                    val boundary = when {
                        events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) -> "transition"
                        events.contains(Player.EVENT_POSITION_DISCONTINUITY) -> discontinuityKind
                        else -> null
                    }
                    bufferingTracker.update(
                        nextMediaId = player.currentMediaItem?.mediaId,
                        nextIndex = player.currentMediaItemIndex,
                        buffering = player.playbackState == Player.STATE_BUFFERING,
                        ready = player.playbackState == Player.STATE_READY,
                        boundary = boundary,
                    ).forEach { event ->
                        if (event.phase == "cancel") {
                            // The player's live position may already belong to the next item.
                            Timber.tag("PlaybackHealth").d(
                                "buffering-cancel id=%s generation=%d kind=%s durationMs=%d",
                                event.mediaId, event.generation, event.kind, event.durationMs,
                            )
                        } else {
                            playerReference.get()?.logPlaybackHealth(
                                "buffering-${event.phase} generation=${event.generation} " +
                                    "kind=${event.kind} durationMs=${event.durationMs}",
                            )
                        }
                    }
                }
            }

        val analyticsListener =
            object : AnalyticsListener {
                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    if (!GlobalLog.isEnabled) return
                    val player = playerReference.get() ?: return
                    val outputBufferMs =
                        if (bufferSizeMs == C.TIME_UNSET) "unset" else bufferSizeMs.toString()
                    val playingForMs =
                        playingSinceElapsedMs?.let { SystemClock.elapsedRealtime() - it }
                    if (isAudioResumeArtefact(elapsedSinceLastFeedMs, playingForMs)) {
                        Timber.tag("PlaybackHealth").d(
                            "audio-resume-after-idle id=%s posMs=%d idleMs=%d playingForMs=%d",
                            player.currentMediaItem?.mediaId,
                            player.currentPosition,
                            elapsedSinceLastFeedMs,
                            playingForMs ?: -1L,
                        )
                        return
                    }
                    Timber.tag("PlaybackHealth").e(
                        "AUDIO UNDERRUN id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d isLoading=%s " +
                            "bufferBytes=%d outputBufferMs=%s elapsedSinceLastFeedMs=%d playingForMs=%d",
                        player.currentMediaItem?.mediaId,
                        player.currentPosition,
                        player.bufferedAheadMs(),
                        player.totalBufferedDuration,
                        player.isLoading,
                        bufferSize,
                        outputBufferMs,
                        elapsedSinceLastFeedMs,
                        playingForMs ?: -1L,
                    )
                }

                override fun onAudioSinkError(
                    eventTime: AnalyticsListener.EventTime,
                    audioSinkError: Exception,
                ) {
                    if (!GlobalLog.isEnabled) return
                    val player = playerReference.get()
                    Timber.tag("PlaybackHealth").e(
                        audioSinkError,
                        "audio-sink-error id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d isLoading=%s",
                        player?.currentMediaItem?.mediaId,
                        player?.currentPosition ?: C.TIME_UNSET,
                        player?.bufferedAheadMs() ?: C.TIME_UNSET,
                        player?.totalBufferedDuration ?: C.TIME_UNSET,
                        player?.isLoading ?: false,
                    )
                }
            }

        capsuleOffloadDiagnostics[this] =
            CapsuleOffloadDiagnostics(
                audioOffloadListener = audioOffloadListener,
                playerListener = playerListener,
                analyticsListener = analyticsListener,
            )
        addAudioOffloadListener(audioOffloadListener)
        addListener(playerListener)
        addAnalyticsListener(analyticsListener)
        return true
    }
}

/**
 * Applies Capsule's low-power audio playback policy to an ExoPlayer.
 *
 * Media3 1.9 moved offload configuration into
 * TrackSelectionParameters.AudioOffloadPreferences and removed the old
 * ExoPlayer offload-scheduling toggles. Using the old reflection names left
 * Capsule on normal CPU decoding even when the setting said "enabled".
 *
 * Capsule used to build the music player with WAKE_MODE_NETWORK. That keeps a
 * WifiLock in addition to the CPU wake lock while playback is READY/BUFFERING.
 * Streaming music does not require low-latency Wi-Fi, so force WAKE_MODE_LOCAL
 * on the already-created player. The foreground media service still keeps
 * playback alive with the screen off, but Wi-Fi is free to use its normal
 * power-saving behaviour between network reads.
 */
fun ExoPlayer.setOffloadEnabled(enabled: Boolean) {
    val firstApplication = ensureCapsuleOffloadDiagnostics()

    val mode =
        if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }

    val offloadPreferences =
        TrackSelectionParameters.AudioOffloadPreferences
            .Builder()
            .setAudioOffloadMode(mode)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()

    if (!firstApplication && trackSelectionParameters.audioOffloadPreferences == offloadPreferences) return

    setWakeMode(C.WAKE_MODE_LOCAL)
    trackSelectionParameters =
        trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()

    Timber.tag("AudioOffload").i(
        "Media3 low-power audio policy offload=%s wakeMode=LOCAL",
        enabled,
    )
    logSelectedAudioOffloadCapability("policyChanged")
}
