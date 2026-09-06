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
    guest_name_anchor = "            appNameProvider = { getString(R.string.app_name) },\n"
    guest_name_insert = guest_name_anchor + "            guestNameProvider = { getString(R.string.together_role_guest) },\n"
    if guest_name_anchor not in source:
        raise ValueError("Together controller app-name argument not found")
    if "guestNameProvider =" not in source:
        source = source.replace(guest_name_anchor, guest_name_insert, 1)

    messages_anchor = '''            invalidWebSocketMessage = { "Connection failed: Invalid server websocket URL" },
            hostEventHandler = ::handleTogetherHostEvent,
'''
    messages_insert = '''            invalidWebSocketMessage = { "Connection failed: Invalid server websocket URL" },
            invalidLinkMessage = { getString(R.string.invalid_link) },
            invalidCodeMessage = { getString(R.string.invalid_code) },
            notAllowedMessage = { getString(R.string.not_allowed) },
            hostLeftMessage = { getString(R.string.together_host_left_session) },
            networkUnavailableMessage = { getString(R.string.network_unavailable) },
            hostEventHandler = ::handleTogetherHostEvent,
            remoteStateApplier = ::applyRemoteRoomState,
            guestControlReset = { togetherGuestControl.reset() },
            guestNotice = { message, key -> showTogetherNotice(message, key) },
'''
    if messages_anchor not in source:
        raise ValueError("Together controller callback anchor not found")
    source = source.replace(messages_anchor, messages_insert, 1)

    source = replace_function(
        source,
        "    fun joinTogether(\n",
        '''    fun joinTogether(
        rawLink: String,
        displayName: String,
    ) {
        ensureScopesActive()
        togetherSessionController.joinLan(
            rawLink = rawLink,
            displayName = displayName,
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun joinTogetherOnline(\n",
        '''    fun joinTogetherOnline(
        code: String,
        displayName: String,
    ) {
        ensureScopesActive()
        togetherSessionController.joinOnline(
            code = code,
            displayName = displayName,
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun leaveTogether() {",
        '''    fun leaveTogether() {
        ensureScopesActive()
        togetherSessionController.leave()
    }''',
    )
    source = replace_function(
        source,
        "    private fun startTogetherHeartbeat(",
        "",
    )
    return source


def validate_before(source: str) -> None:
    required = [
        "private val togetherSessionController by lazy",
        "togetherSessionController.startLanHost(",
        "togetherSessionController.startOnlineHost(",
        "fun joinTogether(",
        "TogetherLink.decode(rawLink)",
        "client.events.collect",
        "fun joinTogetherOnline(",
        "api.resolveCode(trimmedCode)",
        "private fun startTogetherHeartbeat(",
        "private suspend fun applyRemoteRoomState(",
        "private suspend fun stopTogetherInternal()",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing Together guest preconditions: " + ", ".join(missing))
    if "togetherSessionController.joinLan(" in source:
        raise SystemExit("Together guest lifecycle already integrated")


def validate_after(source: str) -> None:
    required = [
        "guestNameProvider = { getString(R.string.together_role_guest) }",
        "remoteStateApplier = ::applyRemoteRoomState",
        "guestControlReset = { togetherGuestControl.reset() }",
        "togetherSessionController.joinLan(",
        "togetherSessionController.joinOnline(",
        "togetherSessionController.leave()",
        "private suspend fun applyRemoteRoomState(",
        "private suspend fun stopTogetherInternal()",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))

    forbidden = [
        "TogetherLink.decode(rawLink)",
        "com.nikhil.yt.together.TogetherClient(",
        "client.events.collect",
        "TogetherClientEvent.Welcome",
        "api.resolveCode(trimmedCode)",
        "private fun startTogetherHeartbeat(",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("Together guest lifecycle leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Together guest controller extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Together guest join/event/heartbeat lifecycle moved behind TogetherSessionController")


if __name__ == "__main__":
    main()
