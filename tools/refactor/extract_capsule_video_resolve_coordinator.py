#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
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


def function_range(source: str, signature: str) -> tuple[int, int]:
    start = source.find(signature)
    if start < 0:
        raise ValueError(f"function not found: {signature.strip()}")
    if source.find(signature, start + len(signature)) >= 0:
        raise ValueError(f"function appears more than once: {signature.strip()}")
    open_index = source.find("{", start)
    if open_index < 0:
        raise ValueError(f"opening brace not found: {signature.strip()}")
    return start, find_matching_brace(source, open_index) + 1


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected exactly one match, found {count}")
    return source.replace(old, new, 1)


def transformed(source: str) -> str:
    import_anchor = "import com.nikhil.yt.playback.video.YouTubeVideoResolver\n"
    import_replacement = (
        import_anchor
        + "import com.nikhil.yt.playback.video.CapsuleVideoResolveCoordinator\n"
        + "import com.nikhil.yt.playback.video.CapsuleVideoResolveRequest\n"
    )
    source = replace_once(source, import_anchor, import_replacement, "video resolver import")

    field = "    private var videoResolveJob: Job? = null\n"
    field_replacement = '''    private val videoResolveCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        CapsuleVideoResolveCoordinator(scopeProvider = { scope })
    }
'''
    source = replace_once(source, field, field_replacement, "video resolve job field")

    # Collapse old two-line cancellation sites, then handle the one standalone
    # cancellation before starting a fresh VIDEO resolve.
    source = re.sub(
        r"(?m)^(\s*)videoResolveJob\?\.cancel\(\)\n\1videoResolveJob = null",
        r"\1videoResolveCoordinator.cancel()",
        source,
    )
    source = source.replace("videoResolveJob?.cancel()", "videoResolveCoordinator.cancel()")

    enter_start, enter_end = function_range(source, "    private fun enterCapsuleVideoMode() {")
    enter = source[enter_start:enter_end]

    assignment_marker = "        videoResolveJob =\n            scope.launch {"
    assignment = enter.find(assignment_marker)
    if assignment < 0:
        raise ValueError("VIDEO resolve launch assignment not found")
    launch_open = enter.find("{", assignment + assignment_marker.find("scope.launch"))
    launch_end = find_matching_brace(enter, launch_open)
    launch_body = enter[launch_open + 1 : launch_end]

    result_marker = "                resolved.onFailure { throwable ->"
    result_start = launch_body.find(result_marker)
    if result_start < 0:
        raise ValueError("VIDEO resolve result chain not found")
    result_chain = launch_body[result_start:].rstrip()
    result_chain = result_chain.replace(
        "                    videoResolveJob = null\n\n",
        "",
    )
    if "videoResolveJob" in result_chain:
        raise ValueError("VIDEO resolve job state leaked into result callback")

    replacement = '''        videoResolveCoordinator.resolve(
            request =
                CapsuleVideoResolveRequest(
                    sourceMediaId = canonicalMediaId,
                    title = sourceTitle,
                    artists = sourceArtists,
                    durationSeconds = sourceDurationSeconds,
                    quality = capsuleVideoQuality,
                ),
            isRelevant = {
                player.currentMediaItem?.mediaId == canonicalMediaId &&
                    videoPlaybackState.value.preferredMode == CapsulePlaybackMode.VIDEO &&
                    videoPlaybackState.value.phase == CapsuleVideoPhase.RESOLVING
            },
            onResult = { resolved ->
''' + result_chain + '''
            },
        )'''

    launch_assignment_end = launch_end + 1
    enter = enter[:assignment] + replacement + enter[launch_assignment_end:]
    source = source[:enter_start] + enter + source[enter_end:]

    return source


def validate_before(source: str) -> None:
    required = [
        "private var videoResolveJob: Job? = null",
        "videoResolveJob =\n            scope.launch {",
        "YouTubeVideoResolver.resolveForSong(",
        "private fun enterCapsuleVideoMode()",
        "private fun leaveCapsuleVideoMode()",
        "private fun restoreOriginalAudioItem(",
        "player.replaceMediaItem(currentIndex, videoItem)",
        "player.replaceMediaItem(currentIndex, original)",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing VIDEO resolve preconditions: " + ", ".join(missing))
    if "private val videoResolveCoordinator by lazy" in source:
        raise SystemExit("CapsuleVideoResolveCoordinator already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val videoResolveCoordinator by lazy",
        "CapsuleVideoResolveCoordinator(scopeProvider = { scope })",
        "videoResolveCoordinator.resolve(",
        "CapsuleVideoResolveRequest(",
        "videoResolveCoordinator.cancel()",
        "isRelevant = {",
        "player.replaceMediaItem(currentIndex, videoItem)",
        "player.replaceMediaItem(currentIndex, original)",
        "private fun enterCapsuleVideoMode()",
        "private fun restoreOriginalAudioItem(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed VIDEO markers: " + ", ".join(missing))

    forbidden = [
        "videoResolveJob",
        "YouTubeVideoResolver.resolveForSong(\n                            sourceMediaId = canonicalMediaId",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("VIDEO resolve ownership leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Capsule VIDEO resolve extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Latest-only VIDEO resolve work moved behind CapsuleVideoResolveCoordinator")


if __name__ == "__main__":
    main()
