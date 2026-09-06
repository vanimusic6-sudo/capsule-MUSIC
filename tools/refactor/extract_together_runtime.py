#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

DEFAULT_PATH = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_function(text: str, signature: str, replacement: str) -> str:
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
    return text[:start] + replacement + text[end:]


def replace_identifier(text: str, name: str, replacement: str) -> str:
    pattern = re.compile(rf"\b{re.escape(name)}\b")
    count = len(pattern.findall(text))
    if count == 0:
        raise RuntimeError(f"identifier {name}: expected at least one use")
    return pattern.sub(replacement, text)


def transform(text: str) -> str:
    if "private val playbackPresenceCoordinator by lazy" not in text:
        raise RuntimeError("Step 5 playback presence extraction must be applied first")
    if "TogetherSessionRuntime(" in text:
        raise RuntimeError("Together runtime extraction is already applied")

    text = replace_once(
        text,
        "import com.nikhil.yt.innertube.models.WatchEndpoint\n",
        "import com.nikhil.yt.innertube.models.WatchEndpoint\n"
        "import com.nikhil.yt.together.TogetherSessionRuntime\n",
        "import TogetherSessionRuntime",
    )

    start_marker = (
        "    val togetherSessionState = MutableStateFlow<com.nikhil.yt.together.TogetherSessionState>(\n"
    )
    end_marker = "    @Volatile\n    private var togetherLastSentControlAtElapsedMs: Long = 0L\n"
    start = text.find(start_marker)
    end = text.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("could not locate Together runtime field block")

    runtime_block = """    private val togetherRuntime =
        TogetherSessionRuntime { operation, error ->
            reportRecoverableException("MusicService", operation, error)
        }
    val togetherSessionState = togetherRuntime.sessionState
"""
    text = text[:start] + runtime_block + text[end:]

    mappings = {
        "togetherServer": "togetherRuntime.server",
        "togetherOnlineHost": "togetherRuntime.onlineHost",
        "togetherClient": "togetherRuntime.client",
        "togetherBroadcastJob": "togetherRuntime.broadcastJob",
        "togetherOnlineConnectJob": "togetherRuntime.onlineConnectJob",
        "togetherClientEventsJob": "togetherRuntime.clientEventsJob",
        "togetherHeartbeatJob": "togetherRuntime.heartbeatJob",
        "togetherClock": "togetherRuntime.clock",
        "togetherSelfParticipantId": "togetherRuntime.selfParticipantId",
        "togetherLastAppliedQueueHash": "togetherRuntime.lastAppliedQueueHash",
        "togetherIsOnlineSession": "togetherRuntime.isOnlineSession",
        "togetherLastAppliedRoomStateSentAtElapsedMs": "togetherRuntime.lastAppliedRoomStateSentAtElapsedMs",
        "togetherLastRemoteAppliedPlayWhenReady": "togetherRuntime.lastRemoteAppliedPlayWhenReady",
        "togetherLastRemoteAppliedIndex": "togetherRuntime.lastRemoteAppliedIndex",
        "togetherSuppressEchoUntilElapsedMs": "togetherRuntime.suppressEchoUntilElapsedMs",
    }
    for name, replacement in mappings.items():
        text = replace_identifier(text, name, replacement)

    text = replace_once(
        text,
        "    private fun isTogetherApplyingRemote(): Boolean = togetherApplyingRemote\n",
        "    private fun isTogetherApplyingRemote(): Boolean = togetherRuntime.applyingRemote\n",
        "route applying-remote state through runtime",
    )

    text = replace_once(
        text,
        "            togetherApplyingRemote = true\n"
        "            togetherRuntime.suppressEchoUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + 450L\n",
        "            togetherRuntime.beginRemoteApply(android.os.SystemClock.elapsedRealtime())\n",
        "begin remote apply through runtime",
    )
    text = replace_once(
        text,
        "                togetherApplyingRemote = false\n",
        "                togetherRuntime.finishRemoteApply()\n",
        "finish remote apply through runtime",
    )

    stop_replacement = """    private suspend fun stopTogetherInternal() {
        togetherLastSentControlAtElapsedMs = 0L
        togetherLastSentControlAction = null
        togetherPendingGuestControl = null
        togetherRuntime.stopConnections()
    }"""
    text = replace_function(
        text,
        "    private suspend fun stopTogetherInternal()",
        stop_replacement,
    )

    forbidden = (
        "private var togetherServer:",
        "private var togetherOnlineHost:",
        "private var togetherClient:",
        "private var togetherBroadcastJob:",
        "private var togetherOnlineConnectJob:",
        "private var togetherClientEventsJob:",
        "private var togetherHeartbeatJob:",
        "private var togetherClock:",
        "private var togetherSelfParticipantId:",
        "private var togetherLastAppliedQueueHash:",
        "private var togetherIsOnlineSession:",
        "private var togetherApplyingRemote:",
        "private var togetherSuppressEchoUntilElapsedMs:",
        "private var togetherLastAppliedRoomStateSentAtElapsedMs:",
        "private var togetherLastRemoteAppliedPlayWhenReady:",
        "private var togetherLastRemoteAppliedIndex:",
        "togetherApplyingRemote = true",
        "togetherApplyingRemote = false",
        "togetherRuntime.suppressEchoUntilElapsedMs =",
        "togetherClient?.disconnect()",
        "togetherOnlineHost?.disconnect()",
        "togetherServer?.stop()",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy Together runtime ownership remained: {leftovers}")

    required = (
        "import com.nikhil.yt.together.TogetherSessionRuntime",
        "private val togetherRuntime =",
        "TogetherSessionRuntime { operation, error ->",
        "val togetherSessionState = togetherRuntime.sessionState",
        "togetherRuntime.beginRemoteApply(android.os.SystemClock.elapsedRealtime())",
        "togetherRuntime.finishRemoteApply()",
        "togetherRuntime.stopConnections()",
        "togetherRuntime.client",
        "togetherRuntime.server",
        "togetherRuntime.onlineHost",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required Together runtime wiring missing: {missing}")

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
        print("OK: MusicService matches Together runtime extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
