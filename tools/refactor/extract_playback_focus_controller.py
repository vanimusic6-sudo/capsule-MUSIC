#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def find_matching_brace(text: str, open_index: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    line_comment = False
    block_comment = 0
    i = open_index
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if ch == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if ch == "/" and nxt == "*":
                block_comment += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                block_comment -= 1
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            block_comment = 1
            i += 2
            continue
        if ch == '"':
            in_string = True
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unmatched brace")


def replace_function(source: str, signature: str, replacement: str) -> str:
    start = source.find(signature)
    if start < 0:
        raise ValueError(f"function not found: {signature.strip()}")
    if source.find(signature, start + len(signature)) >= 0:
        raise ValueError(f"function appears more than once: {signature.strip()}")
    open_index = source.find("{", start)
    if open_index < 0:
        raise ValueError(f"opening brace not found: {signature.strip()}")
    end = find_matching_brace(source, open_index)
    return source[:start] + replacement + source[end + 1 :]


def transformed(source: str) -> str:
    field_block = '''    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
'''
    field_replacement = '''    private lateinit var audioManager: AudioManager
    private val playbackFocusController by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackFocusController(
            audioManager = audioManager,
            isPlayingProvider = { player.isPlaying },
            onDecision = { decision ->
                audioFocusVolumeFactor.value = decision.volumeFactor
                when (decision.playbackAction) {
                    PlaybackFocusPlaybackAction.NONE -> Unit
                    PlaybackFocusPlaybackAction.PAUSE -> if (player.isPlaying) player.pause()
                    PlaybackFocusPlaybackAction.RESUME -> player.play()
                }
            },
        )
    }
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var autoStartOnBluetoothEnabled = false
'''
    if field_block not in source:
        raise ValueError("audio-focus field block does not match expected source")
    source = source.replace(field_block, field_replacement, 1)

    setup_call = "        setupAudioFocusRequest()\n"
    if setup_call not in source:
        raise ValueError("audio-focus setup call not found")
    source = source.replace(setup_call, "        playbackFocusController.initialize()\n", 1)

    source = replace_function(source, "    private fun setupAudioFocusRequest() {", "")
    source = replace_function(source, "    private fun handleAudioFocusChange(focusChange: Int) {", "")
    source = replace_function(source, "    private fun requestAudioFocus(): Boolean {", "")
    source = replace_function(source, "    private fun abandonAudioFocus() {", "")
    source = replace_function(
        source,
        "    fun hasAudioFocusForPlayback(): Boolean {",
        '''    fun hasAudioFocusForPlayback(): Boolean {
        return playbackFocusController.hasFocus
    }''',
    )

    source = source.replace("requestAudioFocus()", "playbackFocusController.requestFocus()")
    source = source.replace("abandonAudioFocus()", "playbackFocusController.abandonFocus()")

    import_line = "import android.media.AudioFocusRequest\n"
    if import_line not in source:
        raise ValueError("AudioFocusRequest import not found")
    source = source.replace(import_line, "", 1)

    return source


def validate_before(source: str) -> None:
    required = [
        "private var audioFocusRequest: AudioFocusRequest? = null",
        "private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE",
        "private var wasPlayingBeforeAudioFocusLoss = false",
        "private var hasAudioFocus = false",
        "private fun setupAudioFocusRequest()",
        "private fun handleAudioFocusChange(focusChange: Int)",
        "private fun requestAudioFocus(): Boolean",
        "private fun abandonAudioFocus()",
        "fun hasAudioFocusForPlayback(): Boolean",
        "private fun handleDeviceMuteStateChanged()",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing audio-focus preconditions: " + ", ".join(missing))
    if "private val playbackFocusController by lazy" in source:
        raise SystemExit("PlaybackFocusController already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val playbackFocusController by lazy",
        "PlaybackFocusController(",
        "playbackFocusController.initialize()",
        "playbackFocusController.requestFocus()",
        "playbackFocusController.abandonFocus()",
        "return playbackFocusController.hasFocus",
        "private fun handleDeviceMuteStateChanged()",
        "private val bluetoothReceiver",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))

    forbidden = [
        "audioFocusRequest",
        "lastAudioFocusState",
        "wasPlayingBeforeAudioFocusLoss",
        "private var hasAudioFocus",
        "private fun setupAudioFocusRequest()",
        "private fun handleAudioFocusChange(focusChange: Int)",
        "private fun requestAudioFocus(): Boolean",
        "private fun abandonAudioFocus()",
        "import android.media.AudioFocusRequest",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("audio-focus implementation leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Playback focus controller extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Android audio-focus policy moved behind PlaybackFocusController")


if __name__ == "__main__":
    main()
