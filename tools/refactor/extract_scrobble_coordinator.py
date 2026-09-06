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


def replace_listenbrainz_finished_block(source: str) -> str:
    marker = "                    val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)"
    marker_index = source.find(marker)
    if marker_index < 0:
        raise ValueError("ListenBrainz finished-submit marker not found")

    block_start = source.rfind("            ioScope.launch {", 0, marker_index)
    if block_start < 0:
        raise ValueError("ListenBrainz ioScope block not found")
    open_index = source.find("{", block_start)
    block_end = find_matching_brace(source, open_index)

    replacement = '''            scrobbleCoordinator.onPlaybackFinished(
                mediaId = mediaItem.mediaId,
                totalPlayTimeMs = playbackStats.totalPlayTimeMs,
            )'''
    return source[:block_start] + replacement + source[block_end + 1 :]


def insert_destroy(source: str) -> str:
    signature = "    override fun onDestroy() {"
    start = source.find(signature)
    if start < 0:
        raise ValueError("onDestroy not found")
    open_index = source.find("{", start)
    end = find_matching_brace(source, open_index)
    method = source[start : end + 1]
    marker = "        discordPresenceOwner.stop()\n"
    if marker not in method:
        raise ValueError("onDestroy Discord marker not found")
    method = method.replace(marker, marker + "        scrobbleCoordinator.destroy()\n", 1)
    return source[:start] + method + source[end + 1 :]


def transformed(source: str) -> str:
    field_old = "    private var scrobbleManager: com.nikhil.yt.utils.ScrobbleManager? = null\n"
    field_new = '''    private val scrobbleCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ScrobbleCoordinator(
            context = this,
            dataStore = dataStore,
            scopeProvider = { scope },
            ioScopeProvider = { ioScope },
            songProvider = { mediaId -> database.song(mediaId).first() },
            onFailure = { operation, error ->
                reportRecoverableException("MusicService", operation, error)
            },
        )
    }
'''
    if field_old not in source:
        raise ValueError("scrobbleManager field does not match expected source")
    source = source.replace(field_old, field_new, 1)

    config_start_marker = '''        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
'''
    config_end_marker = '''        scope.launch(Dispatchers.IO) {
            if (dataStore.get(PersistentQueueKey, true)) {
'''
    config_start = source.find(config_start_marker)
    if config_start < 0:
        raise ValueError("Last.fm preference block start not found")
    config_end = source.find(config_end_marker, config_start)
    if config_end < 0:
        raise ValueError("Last.fm preference block end not found")
    source = source[:config_start] + "        scrobbleCoordinator.start()\n\n" + source[config_end:]

    source = source.replace("scrobbleManager?.onSongStop()", "scrobbleCoordinator.onSongStop()")
    source = source.replace("scrobbleManager?.onSongStart(", "scrobbleCoordinator.onSongStart(")
    source = source.replace(
        "scrobbleManager?.onPlayerStateChanged(",
        "scrobbleCoordinator.onPlayerStateChanged(",
    )

    source = replace_listenbrainz_finished_block(source)
    source = insert_destroy(source)

    removable_imports = [
        "import com.nikhil.yt.constants.EnableLastFMScrobblingKey\n",
        "import com.nikhil.yt.constants.LastFMUseNowPlaying\n",
        "import com.nikhil.yt.constants.ScrobbleDelayPercentKey\n",
        "import com.nikhil.yt.constants.ScrobbleDelaySecondsKey\n",
        "import com.nikhil.yt.constants.ScrobbleMinSongDurationKey\n",
        "import com.nikhil.yt.lastfm.LastFM\n",
        "import com.nikhil.yt.ui.screens.settings.ListenBrainzManager\n",
    ]
    for item in removable_imports:
        if item not in source:
            raise ValueError(f"expected removable import missing: {item.strip()}")
        source = source.replace(item, "", 1)

    return source


def validate_before(source: str) -> None:
    required = [
        "private var scrobbleManager: com.nikhil.yt.utils.ScrobbleManager? = null",
        "EnableLastFMScrobblingKey",
        "scrobbleManager?.onSongStop()",
        "scrobbleManager?.onSongStart(",
        "scrobbleManager?.onPlayerStateChanged(",
        "ListenBrainzManager.submitFinished(this@MusicService",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing preconditions: " + ", ".join(missing))
    if "private val scrobbleCoordinator" in source:
        raise SystemExit("ScrobbleCoordinator already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val scrobbleCoordinator by lazy",
        "scrobbleCoordinator.start()",
        "scrobbleCoordinator.onSongStop()",
        "scrobbleCoordinator.onSongStart(",
        "scrobbleCoordinator.onPlayerStateChanged(",
        "scrobbleCoordinator.onPlaybackFinished(",
        "scrobbleCoordinator.destroy()",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))

    forbidden = [
        "scrobbleManager",
        "EnableLastFMScrobblingKey",
        "LastFMUseNowPlaying",
        "ScrobbleDelayPercentKey",
        "ScrobbleDelaySecondsKey",
        "ScrobbleMinSongDurationKey",
        "import com.nikhil.yt.lastfm.LastFM",
        "ListenBrainzManager.submitFinished",
        "import com.nikhil.yt.ui.screens.settings.ListenBrainzManager",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("scrobbling implementation leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Scrobble coordinator extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Scrobbling lifecycle and ListenBrainz finish submit moved behind ScrobbleCoordinator")


if __name__ == "__main__":
    main()
