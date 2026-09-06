#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")

OLD = '''            "com.nikhil.yt.ACTION_FORWARD" -> {\n                // Jumps forward 10 seconds, but won't go past the end of the song\n                val newPos = (player.currentPosition + 10000).coerceAtMost(player.duration)\n                player.seekTo(newPos)\n            }\n'''

NEW = '''            "com.nikhil.yt.ACTION_FORWARD" -> {\n                player.seekTo(\n                    forwardSeekPositionMs(\n                        currentPositionMs = player.currentPosition,\n                        durationMs = player.duration,\n                    ),\n                )\n            }\n'''


def validate_before(source: str) -> None:
    required = [
        OLD,
        '"com.nikhil.yt.ACTION_FORWARD"',
        "player.currentPosition",
        "player.duration",
        "CapsuleAudioEngine",
        "ResolvingDataSource",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing forward-seek hardening preconditions: " + ", ".join(missing))
    if "forwardSeekPositionMs(" in source:
        raise SystemExit("forward seek already uses safe helper")


def transform(source: str) -> str:
    count = source.count(OLD)
    if count != 1:
        raise ValueError(f"expected one ACTION_FORWARD block, found {count}")
    return source.replace(OLD, NEW, 1)


def validate_after(source: str) -> None:
    required = [
        '"com.nikhil.yt.ACTION_FORWARD"',
        "forwardSeekPositionMs(",
        "currentPositionMs = player.currentPosition",
        "durationMs = player.duration",
        "CapsuleAudioEngine",
        "ResolvingDataSource",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing hardened forward-seek markers: " + ", ".join(missing))
    if "(player.currentPosition + 10000).coerceAtMost(player.duration)" in source:
        raise SystemExit("unsafe ACTION_FORWARD arithmetic still present")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Forward-seek hardening preconditions satisfied")
        return

    updated = transform(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("ACTION_FORWARD now handles unknown duration and overflow safely")


if __name__ == "__main__":
    main()
