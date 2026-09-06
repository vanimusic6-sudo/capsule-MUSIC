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
    second = source.find(signature, start + len(signature))
    if second >= 0:
        raise ValueError(f"function appears more than once: {signature.strip()}")
    open_index = source.find("{", start)
    if open_index < 0:
        raise ValueError(f"opening brace not found: {signature.strip()}")
    end = find_matching_brace(source, open_index)
    return source[:start] + replacement + source[end + 1 :]


def transformed(source: str) -> str:
    import_anchor = "import com.nikhil.yt.together.TogetherSessionRuntime\n"
    import_new = import_anchor + "import com.nikhil.yt.together.TogetherSessionController\n"
    if import_anchor not in source:
        raise ValueError("TogetherSessionRuntime import not found")
    if "import com.nikhil.yt.together.TogetherSessionController\n" not in source:
        source = source.replace(import_anchor, import_new, 1)

    field_anchor = "    private val togetherGuestControl = TogetherGuestControlCoordinator()\n"
    field_new = field_anchor + '''\

    private val togetherSessionController by lazy(LazyThreadSafetyMode.NONE) {
        TogetherSessionController(
            runtime = togetherRuntime,
            mainScopeProvider = { scope },
            ioScopeProvider = { ioScope },
            hostId = togetherHostId,
            appNameProvider = { getString(R.string.app_name) },
            localIpv4Provider = ::getLocalIpv4Address,
            onlineBaseUrlProvider = {
                com.nikhil.yt.together.TogetherOnlineEndpoint.baseUrlOrNull(dataStore)
            },
            onlineTokenProvider = { TogetherOnlineCredentials.bearerTokenOrNull() },
            clientIdProvider = ::getOrCreateTogetherClientId,
            roomStateProvider = ::buildTogetherRoomState,
            onlineErrorMessage = ::togetherOnlineErrorMessage,
            onlineNotConfiguredMessage = { getString(R.string.together_online_not_configured) },
            tokenMissingMessage = { getString(R.string.together_token_missing) },
            invalidWebSocketMessage = { "Connection failed: Invalid server websocket URL" },
            hostEventHandler = ::handleTogetherHostEvent,
            stopCurrentSession = ::stopTogetherInternal,
            onOnlineFailure = ::reportException,
        )
    }
'''
    if field_anchor not in source:
        raise ValueError("Together guest-control field not found")
    if "private val togetherSessionController by lazy" not in source:
        source = source.replace(field_anchor, field_new, 1)

    source = replace_function(
        source,
        "    fun startTogetherHost(\n",
        '''    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: com.nikhil.yt.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        togetherSessionController.startLanHost(
            port = port,
            displayName = displayName,
            settings = settings,
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun startTogetherOnlineHost(\n",
        '''    fun startTogetherOnlineHost(
        displayName: String,
        settings: com.nikhil.yt.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        togetherSessionController.startOnlineHost(
            displayName = displayName,
            settings = settings,
        )
    }''',
    )
    return source


def validate_before(source: str) -> None:
    required = [
        "private val togetherRuntime =",
        "private val togetherGuestControl = TogetherGuestControlCoordinator()",
        "fun startTogetherHost(",
        "com.nikhil.yt.together.TogetherServer(",
        "server.broadcastRoomState(state)",
        "fun startTogetherOnlineHost(",
        "com.nikhil.yt.together.TogetherOnlineHost(",
        "api.createSession(",
        "onlineHost.broadcastRoomState(state)",
        "private suspend fun applyRemoteRoomState(",
        "private suspend fun buildTogetherRoomState(",
        "private suspend fun handleTogetherHostEvent(",
        "private suspend fun stopTogetherInternal()",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing Together host preconditions: " + ", ".join(missing))
    if "private val togetherSessionController by lazy" in source:
        raise SystemExit("TogetherSessionController already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val togetherSessionController by lazy",
        "togetherSessionController.startLanHost(",
        "togetherSessionController.startOnlineHost(",
        "private suspend fun applyRemoteRoomState(",
        "private suspend fun buildTogetherRoomState(",
        "private suspend fun handleTogetherHostEvent(",
        "private suspend fun stopTogetherInternal()",
        "fun joinTogether(",
        "fun joinTogetherOnline(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))

    forbidden = [
        "com.nikhil.yt.together.TogetherServer(",
        "server.broadcastRoomState(state)",
        "com.nikhil.yt.together.TogetherOnlineHost(",
        "api.createSession(",
        "onlineHost.broadcastRoomState(state)",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("Together host lifecycle leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Together host controller extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Together LAN/online host lifecycle moved behind TogetherSessionController")


if __name__ == "__main__":
    main()
