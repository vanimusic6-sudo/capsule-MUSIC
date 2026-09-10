# Re-trigger Step39 after adding slow-read CDN diagnostics from the captured field underrun.
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

music_service = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
renderers_factory = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/CapsuleAudioRenderersFactory.kt"

text = music_service.read_text(encoding="utf-8")
needle = "DefaultRenderersFactory(this)"
count = text.count(needle)
if count != 1:
    raise SystemExit(f"Expected exactly one main DefaultRenderersFactory(this), found {count}")

# The captured field glitch reported a 750 ms AudioTrack PCM buffer and a
# 1214 ms gap since the renderer last fed it. Keep network/load-control and
# YouTube resolve behavior untouched; only double the local PCM sink capacity.
text = text.replace(needle, "CapsuleAudioRenderersFactory(this)", 1)
music_service.write_text(text, encoding="utf-8")

renderers_factory.write_text(
    '''@file:Suppress("DEPRECATION", "UnsafeOptInUsageError")

package com.nikhil.yt.playback

import android.content.Context
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
internal const val CAPSULE_PCM_AUDIO_TRACK_BUFFER_DURATION_US = 1_500_000

internal class CapsuleAudioRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
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
''',
    encoding="utf-8",
)

print("Step39 PCM AudioTrack underrun fix applied")
