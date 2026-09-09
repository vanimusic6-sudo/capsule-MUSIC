from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MUSIC_SERVICE = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
SHOW_MEDIA_INFO = ROOT / "app/src/main/kotlin/com/nikhil/yt/ui/utils/ShowMediaInfo.kt"
TEST_FILE = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/AudioNormalizationTest.kt"


def replace_braced_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Missing function signature: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Missing opening brace for: {signature}")
    depth = 0
    end = None
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise SystemExit(f"Unbalanced braces for: {signature}")
    return text[:start] + replacement + text[end:]


music = MUSIC_SERVICE.read_text(encoding="utf-8")

# Changing the selected playback identity must not tear down the source that is
# already feeding the AudioTrack. Invalidate only future resolves/prefetches;
# the currently open stream is allowed to finish uninterrupted.
new_reload = '''private fun reloadAudioForClientChange(policy: AudioStreamPolicy) {
        streamRetryJob?.cancel()
        streamRetryJob = null
        playbackRecoveryCoordinator.cancelNetworkRecovery(clearWaiting = false)
        audioResolveCoordinator.invalidatePrefetches()
        audioResolveCoordinator.invalidatePolicy(
            invalidatePrefetch = false,
            onInvalidate = playbackUrlCache::clear,
        )
        playbackRecoveryCoordinator.clearRetryBudget()
        // An explicit selection resets per-track exclusions, not the global
        // bot/rate-limit cooldown. The current already-open stream is preserved;
        // only future resolves use the newly selected profile.
        CapsuleAudioEngine.clearStreamClientFailures()
        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "Audio client selected profile=%s; future resolves updated, current playback preserved",
            policy.playbackClientOverrideId,
        )
        prefetchUpcomingAudio()
    }'''
music = replace_braced_function(
    music,
    "private fun reloadAudioForClientChange(policy: AudioStreamPolicy)",
    new_reload,
)

old_preferred = '''            loudnessDb?.takeIf { it.isFinite() }
                ?: perceptualLoudnessDb?.takeIf { it.isFinite() }'''
new_preferred = '''            perceptualLoudnessDb?.takeIf { it.isFinite() }
                ?: loudnessDb?.takeIf { it.isFinite() }'''
if old_preferred not in music:
    raise SystemExit("TrackLoudness preferred-value block not found")
music = music.replace(old_preferred, new_preferred, 1)

helper_marker = "internal data class TrackLoudness("
if helper_marker not in music:
    raise SystemExit("TrackLoudness declaration not found")
helpers = '''internal const val YOUTUBE_LOUDNESS_REFERENCE_LUFS = -7.0
internal const val NORMALIZATION_TARGET_LUFS = -14.0
internal const val MIN_NORMALIZATION_GAIN_DB = -12.0

/**
 * YouTube's legacy loudnessDb is an offset around a -7 LUFS reference, not a
 * measured loudness value and definitely not a volume percentage. Prefer the
 * perceptual measurement when the player response provides one, matching the
 * semantics used by Metrolist. A raw value of 0 dB therefore means about
 * -7 LUFS, not silence.
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

'''
if "YOUTUBE_LOUDNESS_REFERENCE_LUFS" not in music:
    music = music.replace(helper_marker, helpers + helper_marker, 1)
else:
    raise SystemExit("Loudness helpers already exist; refusing ambiguous re-run")

new_calculate = '''internal fun calculateNormalizationFactor(
    loudness: TrackLoudness?,
    maxSafeGainFactor: Float,
): Float {
    val measuredLufs =
        measuredLoudnessLufs(
            loudnessDb = loudness?.loudnessDb,
            perceptualLoudnessDb = loudness?.perceptualLoudnessDb,
        ) ?: return 1f

    // Balanced normalization: target -14 LUFS, attenuate at most 12 dB and
    // retain Capsule's existing +3 dB safety ceiling for unusually quiet audio.
    val gainDb =
        (NORMALIZATION_TARGET_LUFS - measuredLufs)
            .coerceAtLeast(MIN_NORMALIZATION_GAIN_DB)
    val rawFactor = 10f.pow(gainDb.toFloat() / 20f)
    if (!rawFactor.isFinite() || rawFactor <= 0f) return 1f
    return if (rawFactor > 1f) min(rawFactor, maxSafeGainFactor) else rawFactor
}'''
music = replace_braced_function(
    music,
    "internal fun calculateNormalizationFactor(",
    new_calculate,
)

MUSIC_SERVICE.write_text(music, encoding="utf-8")

ui = SHOW_MEDIA_INFO.read_text(encoding="utf-8")
import_anchor = "import com.nikhil.yt.db.entities.Song\n"
if import_anchor not in ui:
    raise SystemExit("ShowMediaInfo Song import anchor not found")
ui = ui.replace(
    import_anchor,
    import_anchor + "import com.nikhil.yt.playback.measuredLoudnessLufs\n",
    1,
)
locale_anchor = "import android.content.ClipboardManager\n"
if locale_anchor not in ui:
    raise SystemExit("ShowMediaInfo ClipboardManager import anchor not found")
ui = ui.replace(locale_anchor, locale_anchor + "import java.util.Locale\n", 1)

old_loudness_row = 'stringResource(R.string.loudness) to currentFormat?.loudnessDb?.let { "$it dB" },'
new_loudness_row = '''stringResource(R.string.loudness) to currentFormat?.let { format ->
                                measuredLoudnessLufs(
                                    loudnessDb = format.loudnessDb,
                                    perceptualLoudnessDb = format.perceptualLoudnessDb,
                                )?.let { measured ->
                                    String.format(Locale.getDefault(), "%.2f LUFS", measured)
                                }
                            },'''
if old_loudness_row not in ui:
    raise SystemExit("ShowMediaInfo loudness row not found")
ui = ui.replace(old_loudness_row, new_loudness_row, 1)
SHOW_MEDIA_INFO.write_text(ui, encoding="utf-8")

TEST_FILE.parent.mkdir(parents=True, exist_ok=True)
if TEST_FILE.exists():
    raise SystemExit(f"Refusing to overwrite existing test: {TEST_FILE}")
TEST_FILE.write_text(
    '''package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioNormalizationTest {
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
        val factor =
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = -5.0, perceptualLoudnessDb = null),
                maxSafeGainFactor = 1.414f,
            )
        assertEquals(0.7943282, factor.toDouble(), 0.0001)
    }

    @Test
    fun perceptualQuietTrackGetsModerateGain() {
        val factor =
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = null, perceptualLoudnessDb = -16.0),
                maxSafeGainFactor = 1.414f,
            )
        assertEquals(1.2589254, factor.toDouble(), 0.0001)
    }

    @Test
    fun veryQuietTrackStillRespectsExistingSafetyCeiling() {
        val factor =
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = null, perceptualLoudnessDb = -30.0),
                maxSafeGainFactor = 1.414f,
            )
        assertEquals(1.414, factor.toDouble(), 0.0001)
    }

    @Test
    fun veryLoudTrackAttenuationIsLimitedToTwelveDb() {
        val factor =
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = null, perceptualLoudnessDb = 0.0),
                maxSafeGainFactor = 1.414f,
            )
        assertEquals(0.2511886, factor.toDouble(), 0.0001)
    }

    @Test
    fun missingLoudnessKeepsUnityGain() {
        assertEquals(
            1.0,
            calculateNormalizationFactor(null, maxSafeGainFactor = 1.414f).toDouble(),
            0.0001,
        )
    }

    @Test
    fun rawZeroIsNotSilenceAndIsAttenuatedForBalancedTarget() {
        val factor =
            calculateNormalizationFactor(
                TrackLoudness(loudnessDb = 0.0, perceptualLoudnessDb = null),
                maxSafeGainFactor = 1.414f,
            )
        assertEquals(0.4466836, factor.toDouble(), 0.0001)
    }
}
''',
    encoding="utf-8",
)

# Structural safety checks before Gradle gets involved.
updated_music = MUSIC_SERVICE.read_text(encoding="utf-8")
start = updated_music.index("private fun reloadAudioForClientChange(policy: AudioStreamPolicy)")
end = updated_music.index("private fun recreateAudioSources", start)
reload_block = updated_music[start:end]
assert "player.stop()" not in reload_block
assert "recreateAudioSources" not in reload_block
assert "current playback preserved" in reload_block
assert "prefetchUpcomingAudio()" in reload_block
assert "NORMALIZATION_TARGET_LUFS = -14.0" in updated_music
assert "perceptualLoudnessDb?.takeIf" in updated_music
updated_ui = SHOW_MEDIA_INFO.read_text(encoding="utf-8")
assert "%.2f LUFS" in updated_ui
assert "measuredLoudnessLufs" in updated_ui
print("step33 patch applied successfully")
