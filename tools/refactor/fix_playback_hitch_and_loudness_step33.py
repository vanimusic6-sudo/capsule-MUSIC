from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MUSIC = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
MEDIA_INFO = ROOT / "app/src/main/kotlin/com/nikhil/yt/ui/utils/ShowMediaInfo.kt"
TEST = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/AudioNormalizationTest.kt"


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing function: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Missing opening brace: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[:start] + replacement + text[i + 1 :]
    raise SystemExit(f"Unbalanced braces: {signature}")


music = MUSIC.read_text(encoding="utf-8")

music = replace_function(
    music,
    "private fun reloadAudioForClientChange(policy: AudioStreamPolicy)",
    '''private fun reloadAudioForClientChange(policy: AudioStreamPolicy) {
        streamRetryJob?.cancel()
        streamRetryJob = null
        playbackRecoveryCoordinator.cancelNetworkRecovery(clearWaiting = false)
        audioResolveCoordinator.invalidatePrefetches()
        audioResolveCoordinator.invalidatePolicy(
            invalidatePrefetch = false,
            onInvalidate = playbackUrlCache::clear,
        )
        playbackRecoveryCoordinator.clearRetryBudget()
        // Explicit client changes affect future resolves only. The current
        // already-open stream must keep feeding AudioTrack without a reprepare.
        CapsuleAudioEngine.clearStreamClientFailures()
        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "Audio client selected profile=%s; future resolves updated, current playback preserved",
            policy.playbackClientOverrideId,
        )
        prefetchUpcomingAudio()
    }''',
)

old_preferred = '''            loudnessDb?.takeIf { it.isFinite() }
                ?: perceptualLoudnessDb?.takeIf { it.isFinite() }'''
new_preferred = '''            perceptualLoudnessDb?.takeIf { it.isFinite() }
                ?: loudnessDb?.takeIf { it.isFinite() }'''
if old_preferred not in music:
    raise SystemExit("TrackLoudness preferred-value block not found")
music = music.replace(old_preferred, new_preferred, 1)

marker = "internal data class TrackLoudness("
if marker not in music:
    raise SystemExit("TrackLoudness declaration not found")
if "YOUTUBE_LOUDNESS_REFERENCE_LUFS" in music:
    raise SystemExit("Loudness helpers already present")
music = music.replace(
    marker,
    '''internal const val YOUTUBE_LOUDNESS_REFERENCE_LUFS = -7.0
internal const val NORMALIZATION_TARGET_LUFS = -14.0
internal const val MIN_NORMALIZATION_GAIN_DB = -12.0

/**
 * YouTube's legacy loudnessDb is an offset around a -7 LUFS reference, not a
 * measured loudness value. Prefer perceptual loudness when available, matching
 * Metrolist semantics. A raw 0 dB therefore represents about -7 LUFS.
 */
internal fun measuredLoudnessLufs(
    loudnessDb: Double?,
    perceptualLoudnessDb: Double?,
): Double? {
    perceptualLoudnessDb?.takeIf { it.isFinite() }?.let { return it }
    return loudnessDb
        ?.takeIf { it.isFinite() }
        ?.plus(YOUTUBE_LOUDNESS_REFERENCE_LUFS)
}

''' + marker,
    1,
)

music = replace_function(
    music,
    "internal fun calculateNormalizationFactor(",
    '''internal fun calculateNormalizationFactor(
    loudness: TrackLoudness?,
    maxSafeGainFactor: Float,
): Float {
    val measuredLufs =
        measuredLoudnessLufs(
            loudnessDb = loudness?.loudnessDb,
            perceptualLoudnessDb = loudness?.perceptualLoudnessDb,
        ) ?: return 1f

    // Balanced target follows Metrolist's normal music setting: -14 LUFS.
    // Keep the existing +3 dB boost ceiling and cap attenuation at -12 dB.
    val gainDb =
        (NORMALIZATION_TARGET_LUFS - measuredLufs)
            .coerceAtLeast(MIN_NORMALIZATION_GAIN_DB)
    val rawFactor = 10f.pow(gainDb.toFloat() / 20f)
    if (!rawFactor.isFinite() || rawFactor <= 0f) return 1f
    return if (rawFactor > 1f) min(rawFactor, maxSafeGainFactor) else rawFactor
}''',
)
MUSIC.write_text(music, encoding="utf-8")

ui = MEDIA_INFO.read_text(encoding="utf-8")
song_import = "import com.nikhil.yt.db.entities.Song\n"
if song_import not in ui:
    raise SystemExit("ShowMediaInfo Song import not found")
ui = ui.replace(
    song_import,
    song_import + "import com.nikhil.yt.playback.measuredLoudnessLufs\n",
    1,
)
clipboard_import = "import android.content.ClipboardManager\n"
if clipboard_import not in ui:
    raise SystemExit("ShowMediaInfo ClipboardManager import not found")
ui = ui.replace(clipboard_import, clipboard_import + "import java.util.Locale\n", 1)
old_row = 'stringResource(R.string.loudness) to currentFormat?.loudnessDb?.let { "$it dB" },'
new_row = '''stringResource(R.string.loudness) to currentFormat?.let { format ->
                                measuredLoudnessLufs(
                                    loudnessDb = format.loudnessDb,
                                    perceptualLoudnessDb = format.perceptualLoudnessDb,
                                )?.let { measured ->
                                    String.format(Locale.getDefault(), "%.2f LUFS", measured)
                                }
                            },'''
if old_row not in ui:
    raise SystemExit("ShowMediaInfo loudness row not found")
MEDIA_INFO.write_text(ui.replace(old_row, new_row, 1), encoding="utf-8")

# Replace the existing normalization regression tests; preserve the offload guard
# test that already shares this small playback-policy test file.
TEST.write_text(
    '''package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNormalizationTest {
    @Test
    fun missingOrInvalidMetadataKeepsUnityGain() {
        assertEquals(1f, calculateNormalizationFactor(null, 1.414f), 0.0001f)
        assertEquals(
            1f,
            calculateNormalizationFactor(TrackLoudness(Double.NaN, null), 1.414f),
            0.0001f,
        )
    }

    @Test
    fun rawYoutubeLoudnessUsesMinusSevenLufsReference() {
        assertEquals(-12.0, measuredLoudnessLufs(-5.0, null)!!, 0.0001)
        assertEquals(-7.0, measuredLoudnessLufs(0.0, null)!!, 0.0001)
    }

    @Test
    fun perceptualMeasurementWinsOverLegacyRawValue() {
        assertEquals(-16.0, measuredLoudnessLufs(-5.0, -16.0)!!, 0.0001)
    }

    @Test
    fun balancedTargetAttenuatesTypicalLegacyYoutubeValue() {
        val factor = calculateNormalizationFactor(TrackLoudness(-5.0, null), 1.414f)
        assertEquals(0.7943282f, factor, 0.0001f)
    }

    @Test
    fun perceptualQuietTrackGetsModerateGain() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, -16.0), 1.414f)
        assertEquals(1.2589254f, factor, 0.0001f)
    }

    @Test
    fun excessiveBoostStillUsesExistingThreeDbCeiling() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, -30.0), 1.414f)
        assertEquals(1.414f, factor, 0.0001f)
    }

    @Test
    fun attenuationIsLimitedToTwelveDb() {
        val factor = calculateNormalizationFactor(TrackLoudness(null, 0.0), 1.414f)
        assertEquals(0.2511886f, factor, 0.0001f)
    }

    @Test
    fun rawZeroIsNotSilence() {
        val factor = calculateNormalizationFactor(TrackLoudness(0.0, null), 1.414f)
        assertEquals(0.4466836f, factor, 0.0001f)
    }

    @Test
    fun offloadIsDisabledWhileCrossfadeIsActive() {
        assertTrue(shouldEnableAudioOffload(requested = true, crossfadeDurationMs = 0))
        assertFalse(shouldEnableAudioOffload(requested = true, crossfadeDurationMs = 1_000))
        assertFalse(shouldEnableAudioOffload(requested = false, crossfadeDurationMs = 0))
    }
}
''',
    encoding="utf-8",
)

updated = MUSIC.read_text(encoding="utf-8")
start = updated.index("private fun reloadAudioForClientChange(policy: AudioStreamPolicy)")
end = updated.index("private fun recreateAudioSources", start)
block = updated[start:end]
assert "player.stop()" not in block
assert "recreateAudioSources" not in block
assert "current playback preserved" in block
assert "prefetchUpcomingAudio()" in block
assert "NORMALIZATION_TARGET_LUFS = -14.0" in updated
assert "perceptualLoudnessDb?.takeIf" in updated
updated_ui = MEDIA_INFO.read_text(encoding="utf-8")
assert "%.2f LUFS" in updated_ui
assert "measuredLoudnessLufs" in updated_ui
print("step33 patch applied successfully")
