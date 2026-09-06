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


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected exactly one match, found {count}")
    return source.replace(old, new, 1)


def transformed(source: str) -> str:
    connectivity_fields = '''    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)
    private var networkRecoveryJob: Job? = null
'''
    connectivity_replacement = '''    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection: MutableStateFlow<Boolean>
        get() = playbackRecoveryCoordinator.waitingForNetworkConnection
    private val isNetworkConnected = MutableStateFlow(false)
'''
    source = replace_once(
        source,
        connectivity_fields,
        connectivity_replacement,
        "network-recovery fields",
    )

    retry_fields = '''    private val playbackRetryBudget = PlaybackRetryBudget()
    private var streamRetryJob: Job? = null
'''
    retry_replacement = '''    private val playbackRecoveryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackRecoveryCoordinator(
            scopeProvider = { scope },
            maxConsecutiveTrackFailures = MAX_CONSECUTIVE_TRACK_FAILURES,
            currentMediaIdProvider = { player.currentMediaItem?.mediaId },
            playWhenReadyProvider = { player.playWhenReady },
            currentIndexProvider = { player.currentMediaItemIndex },
            currentPositionProvider = { player.currentPosition },
            connectedProvider = { connectivityObserver.isCurrentlyConnected() },
            playbackBlockedProvider = {
                CapsuleAudioEngine.playbackBlockedExceptionOrNull() != null
            },
            healthyPlaybackProvider = { mediaId ->
                player.currentMediaItem?.mediaId == mediaId &&
                    player.playbackState == Player.STATE_READY &&
                    player.isPlaying
            },
            pausePlayback = { player.pause() },
            preparePlayback = { player.prepare() },
            healthyPlaybackDelayMs = HEALTHY_PLAYBACK_RESET_MS,
        )
    }
    private var streamRetryJob: Job? = null
'''
    source = replace_once(source, retry_fields, retry_replacement, "retry fields")

    failure_fields = '''    private val trackFailureGuard =
        ConsecutiveTrackFailureGuard(MAX_CONSECUTIVE_TRACK_FAILURES)
    private var trackFailureResetJob: Job? = null

'''
    source = replace_once(source, failure_fields, "", "track-failure fields")

    network_collector = '''        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    if (networkRecoveryJob?.isActive != true) waitOnNetworkError()
                }
            }
        }
'''
    network_collector_replacement = '''        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                playbackRecoveryCoordinator.onConnectivityChanged(isConnected)
            }
        }
'''
    source = replace_once(
        source,
        network_collector,
        network_collector_replacement,
        "network-status collector",
    )

    source = replace_function(source, "    private fun waitOnNetworkError() {", "")
    source = source.replace(
        "waitOnNetworkError()",
        "playbackRecoveryCoordinator.recoverFromNetworkError()",
    )

    source = source.replace(
        "playbackRetryBudget.nextDelayMs(",
        "playbackRecoveryCoordinator.nextRetryDelayMs(",
    )
    source = source.replace(
        "playbackRetryBudget.reset(",
        "playbackRecoveryCoordinator.resetRetry(",
    )
    source = source.replace(
        "playbackRetryBudget.clear()",
        "playbackRecoveryCoordinator.clearRetryBudget()",
    )

    # Preserve the exact waiting-state semantics of each old cancellation site.
    # Sites that explicitly cleared waiting continue to do so. A bare job
    # cancellation (notably client/policy reload) keeps the waiting flag intact.
    source = re.sub(
        r"(?m)^(\s*)networkRecoveryJob\?\.cancel\(\)\n\1networkRecoveryJob = null\n\1waitingForNetworkConnection\.value = false",
        r"\1playbackRecoveryCoordinator.cancelNetworkRecovery()",
        source,
    )
    source = re.sub(
        r"(?m)^(\s*)networkRecoveryJob\?\.cancel\(\)\n\1waitingForNetworkConnection\.value = false",
        r"\1playbackRecoveryCoordinator.cancelNetworkRecovery()",
        source,
    )
    source = re.sub(
        r"(?m)^(\s*)networkRecoveryJob\?\.cancel\(\)\n\1networkRecoveryJob = null",
        r"\1playbackRecoveryCoordinator.cancelNetworkRecovery(clearWaiting = false)",
        source,
    )

    state_guard = '''    val activeMediaId = player.currentMediaItem?.mediaId
    if (playbackState != Player.STATE_READY) {
        trackFailureResetJob?.cancel()
        trackFailureResetJob = null
    } else if (player.isPlaying && activeMediaId != null) {
        scheduleTrackFailureGuardReset(activeMediaId)
    }
'''
    state_guard_replacement = '''    val activeMediaId = player.currentMediaItem?.mediaId
    playbackRecoveryCoordinator.onPlaybackActivity(
        mediaId = activeMediaId,
        ready = playbackState == Player.STATE_READY,
        playing = player.isPlaying,
    )
'''
    source = replace_once(source, state_guard, state_guard_replacement, "playback-state guard")

    playing_guard = '''        val activeMediaId = player.currentMediaItem?.mediaId
        if (isPlaying && player.playbackState == Player.STATE_READY && activeMediaId != null) {
            scheduleTrackFailureGuardReset(activeMediaId)
        } else {
            trackFailureResetJob?.cancel()
            trackFailureResetJob = null
        }
'''
    playing_guard_replacement = '''        val activeMediaId = player.currentMediaItem?.mediaId
        playbackRecoveryCoordinator.onPlaybackActivity(
            mediaId = activeMediaId,
            ready = player.playbackState == Player.STATE_READY,
            playing = isPlaying,
        )
'''
    source = replace_once(source, playing_guard, playing_guard_replacement, "is-playing guard")

    source = replace_function(source, "    private fun scheduleTrackFailureGuardReset(mediaId: String) {", "")

    terminal_replacement = '''    private fun handleTerminalPlaybackError() {
        val mediaId = player.currentMediaItem?.mediaId
        val decision =
            playbackRecoveryCoordinator.recordTerminalFailure(
                mediaId = mediaId,
                autoSkipEnabled = dataStore.get(AutoSkipNextOnErrorKey, false),
            )

        if (decision.circuitOpenedNow) {
            Timber.tag("MusicService").e(
                "Playback failure circuit opened after %d tracks; queue traversal stopped at id=%s",
                decision.failureCount,
                mediaId,
            )
            Toast.makeText(
                this,
                getString(R.string.error_too_many_failed_tracks),
                Toast.LENGTH_LONG,
            ).show()
        } else if (!decision.mayAutoSkip) {
            Timber.tag("MusicService").w(
                "Playback failure circuit suppressed another skip id=%s count=%d open=%s",
                mediaId,
                decision.failureCount,
                decision.circuitOpen,
            )
        }

        when (decision.action) {
            TerminalPlaybackAction.SKIP -> skipOnError()
            TerminalPlaybackAction.STOP -> stopOnError()
        }
    }'''
    source = replace_function(
        source,
        "    private fun handleTerminalPlaybackError() {",
        terminal_replacement,
    )

    stop_clear_guard = '''        trackFailureResetJob?.cancel()
        trackFailureResetJob = null
        trackFailureGuard.reset()
'''
    source = replace_once(
        source,
        stop_clear_guard,
        "        playbackRecoveryCoordinator.resetFailureGuard()\n",
        "stop-and-clear failure reset",
    )

    return source


def validate_before(source: str) -> None:
    required = [
        "val waitingForNetworkConnection = MutableStateFlow(false)",
        "private var networkRecoveryJob: Job? = null",
        "private val playbackRetryBudget = PlaybackRetryBudget()",
        "private val trackFailureGuard =",
        "private var trackFailureResetJob: Job? = null",
        "private fun waitOnNetworkError()",
        "private fun scheduleTrackFailureGuardReset(mediaId: String)",
        "private fun handleTerminalPlaybackError()",
        "private var streamRetryJob: Job? = null",
        "override fun onPlayerError(error: PlaybackException)",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing playback-recovery preconditions: " + ", ".join(missing))
    if "private val playbackRecoveryCoordinator by lazy" in source:
        raise SystemExit("PlaybackRecoveryCoordinator already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val playbackRecoveryCoordinator by lazy",
        "PlaybackRecoveryCoordinator(",
        "get() = playbackRecoveryCoordinator.waitingForNetworkConnection",
        "playbackRecoveryCoordinator.onConnectivityChanged(isConnected)",
        "playbackRecoveryCoordinator.recoverFromNetworkError()",
        "playbackRecoveryCoordinator.nextRetryDelayMs(",
        "playbackRecoveryCoordinator.onPlaybackActivity(",
        "playbackRecoveryCoordinator.recordTerminalFailure(",
        "TerminalPlaybackAction.SKIP -> skipOnError()",
        "private var streamRetryJob: Job? = null",
        "override fun onPlayerError(error: PlaybackException)",
        "private fun scheduleStreamRefreshRetry(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))

    forbidden = [
        "private var networkRecoveryJob: Job? = null",
        "private val playbackRetryBudget = PlaybackRetryBudget()",
        "private val trackFailureGuard =",
        "private var trackFailureResetJob: Job? = null",
        "private fun waitOnNetworkError()",
        "private fun scheduleTrackFailureGuardReset(mediaId: String)",
        "networkRecoveryJob?.cancel()",
        "playbackRetryBudget.nextDelayMs(",
        "playbackRetryBudget.reset(",
        "playbackRetryBudget.clear()",
        "trackFailureGuard.recordFailure(",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("playback-recovery state leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Playback recovery coordinator extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Playback recovery state moved behind PlaybackRecoveryCoordinator")


if __name__ == "__main__":
    main()
