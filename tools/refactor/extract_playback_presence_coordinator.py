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


def remove_function(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing function: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise RuntimeError(f"missing opening brace for: {signature}")
    depth = 0
    end = None
    for index in range(brace, len(text)):
        ch = text[index]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise RuntimeError(f"unterminated function: {signature}")
    while end < len(text) and text[end] == "\n":
        end += 1
    return text[:start] + text[end:]


def replace_scope_launch_containing(text: str, needle: str, call: str) -> str:
    if text.count(needle) != 1:
        raise RuntimeError(f"presence block needle {needle!r}: expected exactly 1 match, found {text.count(needle)}")
    needle_pos = text.index(needle)
    start = text.rfind("scope.launch {", 0, needle_pos)
    if start < 0:
        raise RuntimeError(f"could not find enclosing scope.launch for {needle!r}")

    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    if indent.strip():
        raise RuntimeError(f"unexpected text before scope.launch for {needle!r}: {indent!r}")

    brace = text.find("{", start)
    depth = 0
    end = None
    for index in range(brace, len(text)):
        ch = text[index]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise RuntimeError(f"unterminated scope.launch for {needle!r}")

    if end < len(text) and text[end] == "\n":
        end += 1
    return text[:line_start] + indent + call + "\n" + text[end:]


def transform(text: str) -> str:
    if "private val discordPresenceOwner by lazy" not in text:
        raise RuntimeError("Step 4 Discord presence ownership extraction must be applied first")
    if "PlaybackPresenceCoordinator(" in text:
        raise RuntimeError("Playback presence coordinator extraction is already applied")

    text = replace_once(
        text,
        "import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager\n",
        "",
        "remove direct DiscordPresenceManager import",
    )
    text = replace_once(
        text,
        "import com.nikhil.yt.playback.presence.DiscordPresenceOwner\n",
        "import com.nikhil.yt.playback.presence.DiscordPresenceOwner\n"
        "import com.nikhil.yt.playback.presence.PlaybackPresenceCoordinator\n",
        "import PlaybackPresenceCoordinator",
    )

    owner_tail = """            onFailure = { operation, error ->
                Timber.tag(\"MusicService\").e(error, operation)
            },
        )
    }
    @Volatile
    private var lastPresenceUpdateTime = 0L
"""
    coordinator_tail = """            onFailure = { operation, error ->
                Timber.tag(\"MusicService\").e(error, operation)
            },
        )
    }
    private val playbackPresenceCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackPresenceCoordinator(
            context = this,
            scopeProvider = { scope },
            discordOwner = discordPresenceOwner,
            currentMediaIdProvider = { player.currentMediaItem?.mediaId },
            songProvider = { mediaId ->
                val stored =
                    if (mediaId != null) {
                        withContext(Dispatchers.IO) { database.song(mediaId).first() }
                    } else {
                        null
                    }
                stored
                    ?: player.currentMetadata
                        ?.takeIf { metadata -> mediaId == null || metadata.id == mediaId }
                        ?.let(::createTransientSongFromMedia)
            },
            positionProvider = { player.currentPosition },
            isPausedProvider = { !player.isPlaying },
            listenBrainzEnabledProvider = { dataStore.get(ListenBrainzEnabledKey, false) },
            listenBrainzTokenProvider = { dataStore.get(ListenBrainzTokenKey, \"\") },
            onFailure = { operation, error ->
                Timber.tag(\"MusicService\").v(error, operation)
            },
        )
    }
"""
    text = replace_once(
        text,
        owner_tail,
        coordinator_tail,
        "install playback presence coordinator",
    )

    text = remove_function(text, "    private fun canUpdatePresence()")

    text = replace_scope_launch_containing(
        text,
        "immediate presence update returned false — attempting restart",
        "playbackPresenceCoordinator.requestImmediateUpdate()",
    )
    text = replace_scope_launch_containing(
        text,
        "immediate presence update failed on transition",
        "playbackPresenceCoordinator.requestImmediateUpdate()",
    )
    text = replace_scope_launch_containing(
        text,
        "immediate presence update failed for isPlaying/mediaTransition",
        "playbackPresenceCoordinator.requestImmediateUpdate()",
    )

    text = replace_once(
        text,
        "        const val MIN_PRESENCE_UPDATE_INTERVAL = 20_000L\n",
        "",
        "remove service presence debounce constant",
    )

    forbidden = (
        "import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager",
        "DiscordPresenceManager.",
        "lastPresenceUpdateTime",
        "private fun canUpdatePresence",
        "ListenBrainzManager.submitPlayingNow",
        "immediate presence update returned false",
        "immediate presence update failed on transition",
        "immediate presence update failed for isPlaying/mediaTransition",
        "MIN_PRESENCE_UPDATE_INTERVAL",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy immediate presence logic remained: {leftovers}")

    required = (
        "import com.nikhil.yt.playback.presence.PlaybackPresenceCoordinator",
        "private val playbackPresenceCoordinator by lazy",
        "PlaybackPresenceCoordinator(",
        "playbackPresenceCoordinator.requestImmediateUpdate()",
        "ListenBrainzManager.submitFinished",
        "discordPresenceOwner.ensure()",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required playback presence wiring missing: {missing}")

    if text.count("playbackPresenceCoordinator.requestImmediateUpdate()") != 3:
        raise RuntimeError(
            "expected exactly 3 immediate playback-presence signals, found "
            f"{text.count('playbackPresenceCoordinator.requestImmediateUpdate()')}"
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
        print("OK: MusicService matches playback-presence coordinator extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
