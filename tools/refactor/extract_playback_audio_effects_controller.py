#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

DEFAULT_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_exact_count(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected exactly {expected} matches, found {count}")
    return text.replace(old, new)


def function_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing function: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise RuntimeError(f"missing opening brace for: {signature}")

    depth = 0
    end = None
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise RuntimeError(f"unterminated function: {signature}")

    while end < len(text) and text[end] == "\n":
        end += 1
    return start, end


def remove_function(text: str, signature: str) -> str:
    start, end = function_bounds(text, signature)
    return text[:start] + text[end:]


def replace_function(text: str, signature: str, replacement: str) -> str:
    start, end = function_bounds(text, signature)
    return text[:start] + replacement + text[end:]


def transform(text: str) -> str:
    if "private val togetherGuestControl = TogetherGuestControlCoordinator()" not in text:
        raise RuntimeError("Step 8 Together guest-control extraction must be applied first")
    if "PlaybackAudioEffectsController(" in text:
        raise RuntimeError("Playback audio-effects extraction is already applied")

    for direct_import in (
        "import android.media.audiofx.BassBoost\n",
        "import android.media.audiofx.Equalizer\n",
        "import android.media.audiofx.LoudnessEnhancer\n",
        "import android.media.audiofx.Virtualizer\n",
    ):
        text = replace_once(text, direct_import, "", f"remove {direct_import.strip()}")

    old_fields = """    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private val desiredEqSettings =
        MutableStateFlow(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
            ),
        )

    private var audioEffectsSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
"""
    new_fields = """    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    private val audioEffectsController =
        PlaybackAudioEffectsController { operation, error ->
            reportRecoverableException(\"MusicService\", operation, error)
        }
    val eqCapabilities = audioEffectsController.capabilities
"""
    text = replace_once(text, old_fields, new_fields, "replace audio-effect runtime fields")

    old_collector = """            .collectLatest(scope) { settings ->
                desiredEqSettings.value = settings
                applyEqSettingsToEffects(settings)
            }
"""
    new_collector = """            .collectLatest(scope) { settings ->
                audioEffectsController.applySettings(settings)
            }
"""
    text = replace_once(text, old_collector, new_collector, "delegate EQ settings application")

    old_flat = """            val caps = eqCapabilities.value
            val bandCount = caps?.bandCount ?: runCatching { equalizer?.numberOfBands?.toInt() }.getOrNull() ?: 0
"""
    new_flat = """            val bandCount = audioEffectsController.currentBandCount()
"""
    text = replace_once(text, old_flat, new_flat, "delegate flat-preset band count")

    system_preset = """    fun applySystemEqPreset(presetIndex: Int) {
        scope.launch {
            val levels =
                audioEffectsController.applySystemPreset(
                    sessionId = player.audioSessionId,
                    presetIndex = presetIndex,
                ) ?: return@launch

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch

            ioScope.launch {
                dataStore.edit { prefs ->
                    prefs[EqualizerEnabledKey] = true
                    prefs[EqualizerBandLevelsMbKey] = encoded
                    prefs[EqualizerSelectedProfileIdKey] = \"system:$presetIndex\"
                }
            }
        }
    }

"""
    text = replace_function(text, "    fun applySystemEqPreset(presetIndex: Int)", system_preset)

    for signature in (
        "    private fun resampleLevelsByIndex(levelsMb: List<Int>, targetCount: Int)",
        "    private fun updateEqCapabilitiesFromEffect(eq: Equalizer)",
        "    private fun releaseAudioEffects()",
        "    private fun ensureAudioEffects(sessionId: Int)",
        "    private fun applyEqSettingsToEffects(settings: EqSettings)",
    ):
        text = remove_function(text, signature)

    text = replace_exact_count(
        text,
        "        ensureAudioEffects(sessionId)\n",
        "        audioEffectsController.ensure(sessionId)\n",
        1,
        "delegate opening audio-effect session",
    )
    text = replace_exact_count(
        text,
        "            ensureAudioEffects(newSessionId)\n",
        "            audioEffectsController.ensure(newSessionId)\n",
        1,
        "delegate changed audio session",
    )
    text = replace_exact_count(
        text,
        "        releaseAudioEffects()\n",
        "        audioEffectsController.release()\n",
        2,
        "delegate audio-effect release",
    )

    forbidden = (
        "import android.media.audiofx.BassBoost",
        "import android.media.audiofx.Equalizer",
        "import android.media.audiofx.LoudnessEnhancer",
        "import android.media.audiofx.Virtualizer",
        "private var audioEffectsSessionId",
        "private var equalizer:",
        "private var bassBoost:",
        "private var virtualizer:",
        "private var loudnessEnhancer:",
        "desiredEqSettings",
        "private fun releaseAudioEffects",
        "private fun ensureAudioEffects",
        "private fun applyEqSettingsToEffects",
        "private fun updateEqCapabilitiesFromEffect",
        "private fun resampleLevelsByIndex",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy audio-effect ownership remained: {leftovers}")

    required = (
        "private val audioEffectsController =",
        "PlaybackAudioEffectsController { operation, error ->",
        "val eqCapabilities = audioEffectsController.capabilities",
        "audioEffectsController.applySettings(settings)",
        "audioEffectsController.currentBandCount()",
        "audioEffectsController.applySystemPreset(",
        "audioEffectsController.ensure(sessionId)",
        "audioEffectsController.ensure(newSessionId)",
        "audioEffectsController.release()",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required audio-effect controller wiring missing: {missing}")

    if text.count("audioEffectsController.release()") != 2:
        raise RuntimeError(
            "expected exactly two service release sites (close session + destroy), found "
            f"{text.count('audioEffectsController.release()')}"
        )

    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    path: Path = args.file
    original = path.read_text(encoding="utf-8")
    updated = transform(original)

    if args.check:
        print("OK: MusicService matches playback audio-effects extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
