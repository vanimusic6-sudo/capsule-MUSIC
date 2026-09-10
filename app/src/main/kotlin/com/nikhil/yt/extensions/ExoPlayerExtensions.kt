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

        var bufferingStartedAtElapsedMs: Long? = null
        val playerListener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    playerReference.get()?.logSelectedAudioOffloadCapability("tracksChanged")
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!GlobalLog.isEnabled) return
                    val player = playerReference.get() ?: return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            bufferingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                            player.logPlaybackHealth("buffering-start")
                        }

                        Player.STATE_READY -> {
                            val startedAt = bufferingStartedAtElapsedMs ?: return
                            bufferingStartedAtElapsedMs = null
                            val durationMs =
                                (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                            player.logPlaybackHealth("buffering-end durationMs=$durationMs")
                        }

                        else -> bufferingStartedAtElapsedMs = null
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
                    Timber.tag("PlaybackHealth").e(
                        "AUDIO UNDERRUN id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d " +
                            "isLoading=%s bufferBytes=%d outputBufferMs=%s elapsedSinceLastFeedMs=%d",
                        player.currentMediaItem?.mediaId,
                        player.currentPosition,
                        player.bufferedAheadMs(),
                        player.totalBufferedDuration,
                        player.isLoading,
                        bufferSize,
                        outputBufferMs,
                        elapsedSinceLastFeedMs,
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
