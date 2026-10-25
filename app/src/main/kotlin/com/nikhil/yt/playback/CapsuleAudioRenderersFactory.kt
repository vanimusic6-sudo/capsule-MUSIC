@file:Suppress("DEPRECATION", "UnsafeOptInUsageError")

package com.nikhil.yt.playback

import android.content.Context
import android.os.Build
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/**
 * Capsule's main renderer factory with a modest PCM AudioTrack safety margin.
 *
 * Media3 1.9.2 clamps the 1x PCM AudioTrack buffer between 250 ms and 750 ms.
 * A real field underrun showed that 750 ms output buffer being starved for
 * 1214 ms. We intentionally set the 1x PCM min/max to 1.5 s and leave the
 * PCM multiplier, compressed passthrough/offload buffers, Media3 source
 * buffering, CDN transport and YouTube request pacing at their defaults.
 */
/**
 * Android 17 introduces a native in-process Opus decoder, but still normally
 * prioritizes the out-of-process c2.android.opus.decoder. On HONOR Android 17
 * this legacy path was observed to throw during queueInputBuffer.
 *
 * Prefer the new low-latency path ONLY if the platform already lists it as
 * supported, ONLY when the legacy Opus decoder would otherwise be chosen.
 * Do not alter AAC, hardware Opus, old Android, or secure/tunneled playback.
 */
internal fun inProcessOpusPreferenceIndex(
    codecNames: List<String>, apiLevel: Int, mimeType: String,
    requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean,
): Int {
    if (apiLevel < 37 || mimeType != "audio/opus" ||
        requiresSecureDecoder || requiresTunnelingDecoder ||
        codecNames.firstOrNull() != "c2.android.opus.decoder") return -1
    return codecNames.indexOf("c2.android.inproc.opus.decoder").takeIf { it > 0 } ?: -1
}

internal object CapsuleOpusCodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val original = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
        )
        val index = inProcessOpusPreferenceIndex(
            original.map { it.name }, Build.VERSION.SDK_INT, mimeType,
            requiresSecureDecoder, requiresTunnelingDecoder,
        )
        if (index == -1) return original
        return listOf(original[index]) + original.filterIndexed { i, _ -> i != index }
    }
}

internal const val CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US = 1_500_000

internal class CapsuleAudioRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    init {
        setMediaCodecSelector(CapsuleOpusCodecSelector)
        // If a supported in-process decoder cannot initialize, retry the
        // original platform codec. Runtime failures remain separately logged.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val pcmBufferSizeProvider =
            DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US)
                .setMaxPcmBufferDurationUs(CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US)
                .build()

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioTrackBufferSizeProvider(pcmBufferSizeProvider)
            .build()
    }
}
