@file:Suppress("UnsafeOptInUsageError")

/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.extensions

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import com.nikhil.yt.App
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private data class CapsuleOffloadDiagnostics(
    val audioOffloadListener: ExoPlayer.AudioOffloadListener,
    val playerListener: Player.Listener,
)

private val capsuleOffloadDiagnostics =
    WeakHashMap<ExoPlayer, CapsuleOffloadDiagnostics>()

private val capsuleOffloadSupportProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    DefaultAudioOffloadSupportProvider(App.instance.applicationContext)
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
        val playerListener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    playerReference.get()?.logSelectedAudioOffloadCapability("tracksChanged")
                }
            }

        capsuleOffloadDiagnostics[this] =
            CapsuleOffloadDiagnostics(
                audioOffloadListener = audioOffloadListener,
                playerListener = playerListener,
            )
        addAudioOffloadListener(audioOffloadListener)
        addListener(playerListener)
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
