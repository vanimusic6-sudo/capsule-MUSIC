@file:Suppress("DEPRECATION", "UnsafeOptInUsageError")

package com.nikhil.yt.playback

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
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
 * 1214 ms. We intentionally set the 1x MUSIC PCM min/max to 2 s for scheduling headroom and leave the
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

internal const val CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US = 2_000_000
/** One short-buffer attempt per studio visit; restore immediately after an underrun.
 * No flush, seek or AudioTrack recreation. Already queued audio drains normally. */
private class CapsuleStudioInteractiveBuffer {
    private var track: AudioTrack? = null
    private var originalFrames = 0
    private var visiting = false
    private var attempted = false
    private var shortened = false
    private var baselineUnderruns = 0
    private var nextRestoreAtMs = 0L

    fun attach(output: AudioTrack, config: AudioSink.AudioTrackConfig) {
        track = if (Build.VERSION.SDK_INT >= 24 && !config.offload && !config.tunneling &&
            config.encoding == C.ENCODING_PCM_16BIT && output.state == AudioTrack.STATE_INITIALIZED) output else null
        originalFrames = track?.bufferSizeInFrames ?: 0
        visiting = false
        attempted = false
        shortened = false
        nextRestoreAtMs = 0L
    }

    fun update(interactive: Boolean) {
        if (Build.VERSION.SDK_INT < 24) return
        // No AudioTrack state queries on ordinary PCM blocks with no pending change.
        if (!interactive && !visiting && !shortened) return
        val output = track ?: return
        if (output.state != AudioTrack.STATE_INITIALIZED || originalFrames <= 0) return
        if (visiting != interactive) {
            visiting = interactive
            attempted = false
        }
        if (shortened && (!interactive || output.underrunCount > baselineUnderruns)) {
            // Stay on the conservative queue for the rest of this visit.
            attempted = interactive
            // If a device clamps a requested size, avoid repeated native resize
            // calls on every decoder buffer. Retry without blocking the renderer.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < nextRestoreAtMs) return
            nextRestoreAtMs = now + 250L
            val restored = runCatching { output.setBufferSizeInFrames(originalFrames) }.getOrNull()
            if (restored != null && restored >= originalFrames) {
                shortened = false
                nextRestoreAtMs = 0L
            }
            return
        }
        if (!interactive || attempted || shortened) return
        attempted = true
        val target = (output.sampleRate.toLong() * 450L / 1000L).toInt().coerceAtLeast(1)
        if (target >= originalFrames) return
        baselineUnderruns = output.underrunCount
        val actual = runCatching { output.setBufferSizeInFrames(target) }.getOrNull()
        shortened = actual != null && actual in 1 until originalFrames
    }
}

internal class CapsuleAudioRenderersFactory(
    context: Context,
    private val readStudioSound: () -> CapsuleStudioSound,
    private val useMusicBuffer: Boolean = true,
    private val readStudioPlan: (String) -> CapsuleTrackSound? = { null },
    private val publishStudioPlan: (String, CapsuleTrackSound) -> Unit = { _, _ -> },
    private val readStudioInteractive: () -> Boolean = { false },
) : DefaultRenderersFactory(context), Player.Listener {
    @Volatile private var studioTrackId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        studioTrackId = mediaItem?.mediaId
    }

    init {
        // The Oct 25 codec A/B capture recorded 31 starvation events with
        // c2.android.inproc.opus.decoder (despite >1 minute of AUDIO buffered),
        // versus zero with AAC. Respect Android's native preference order for
        // long-running MUSIC. The isolated VIDEO soundtrack may still use the
        // in-process decoder for startup, keeping the two experiments separate.
        setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        // Preserve init-time fallback without forcing a particular Opus codec.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val interactiveBuffer = CapsuleStudioInteractiveBuffer()
        val pcmBufferSizeProvider =
            DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US)
                .setMaxPcmBufferDurationUs(CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US)
                .build()

        val processorChain = object : DefaultAudioSink.DefaultAudioProcessorChain() {
            // Keep Media3's own time/silence accounting. EQ sees the final
            // sample frequencies and catches peaks after pitch resampling.
            private val processors = super.getAudioProcessors() + CapsuleStudioEqProcessor(
                {
                    interactiveBuffer.update(readStudioInteractive())
                    readStudioSound()
                },
                { studioTrackId }, readStudioPlan, publishStudioPlan,
            )
            override fun getAudioProcessors(): Array<AudioProcessor> = processors
        }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioProcessorChain(processorChain)
            .setAudioTrackProvider { config, attributes, session, outputContext ->
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, session, outputContext)
                    .also { interactiveBuffer.attach(it, config) }
            }
            .setAudioTrackBufferSizeProvider(
                if (useMusicBuffer) pcmBufferSizeProvider else DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT,
            )
            .build()
    }
}
