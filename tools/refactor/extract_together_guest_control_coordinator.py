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


def replace_all_exact(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected exactly {expected} matches, found {count}")
    return text.replace(old, new)


def replace_between(
    text: str,
    start_marker: str,
    end_marker: str,
    replacement: str,
    label: str,
    *,
    search_from: int = 0,
) -> str:
    start = text.find(start_marker, search_from)
    if start < 0:
        raise RuntimeError(f"{label}: start marker not found")
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start] + replacement + text[end:]


def transform(text: str) -> str:
    if "private val togetherRuntime" not in text or "TogetherSessionRuntime" not in text:
        raise RuntimeError("Step 6 Together runtime extraction must be applied first")
    if "TogetherOnlineCredentials.bearerTokenOrNull()" not in text:
        raise RuntimeError("Step 7 Together credential wiring must be applied first")
    if "TogetherGuestControlCoordinator(" in text:
        raise RuntimeError("Together guest control extraction is already applied")

    text = replace_once(
        text,
        "import com.nikhil.yt.together.TogetherSessionRuntime\n"
        "import com.nikhil.yt.together.TogetherOnlineCredentials\n",
        "import com.nikhil.yt.together.TogetherSessionRuntime\n"
        "import com.nikhil.yt.together.TogetherOnlineCredentials\n"
        "import com.nikhil.yt.together.TogetherGuestControlCoordinator\n",
        "import TogetherGuestControlCoordinator",
    )

    old_fields = """    val togetherSessionState = togetherRuntime.sessionState
    @Volatile
    private var togetherLastSentControlAtElapsedMs: Long = 0L
    @Volatile
    private var togetherLastSentControlAction: com.nikhil.yt.together.ControlAction? = null
    @Volatile
    private var togetherPendingGuestControl: TogetherPendingGuestControl? = null

"""
    new_fields = """    val togetherSessionState = togetherRuntime.sessionState
    private val togetherGuestControl = TogetherGuestControlCoordinator()

"""
    text = replace_once(text, old_fields, new_fields, "replace guest-control fields")

    pending_data_class = """    private data class TogetherPendingGuestControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    )

"""
    text = replace_once(text, pending_data_class, "", "remove pending guest-control data class")

    disabled_reset = """                                        togetherPendingGuestControl = null
                                        togetherLastSentControlAction = null
"""
    text = replace_all_exact(
        text,
        disabled_reset,
        "                                        togetherGuestControl.reset()\n",
        2,
        "replace guest-control-disabled resets",
    )

    request_signature = "    fun requestTogetherControl(action: com.nikhil.yt.together.ControlAction) {"
    request_start = text.find(request_signature)
    if request_start < 0:
        raise RuntimeError("requestTogetherControl function not found")

    request_tracking_start = "        val now = android.os.SystemClock.elapsedRealtime()\n        val lastAction = togetherLastSentControlAction\n"
    request_send_marker = "        client.requestControl(state.sessionId, action)\n"
    replacement = """        val now = android.os.SystemClock.elapsedRealtime()
        if (!togetherGuestControl.registerOutgoing(action, now, togetherRuntime.isOnlineSession)) return

"""
    text = replace_between(
        text,
        request_tracking_start,
        request_send_marker,
        replacement,
        "replace requestTogetherControl bookkeeping",
        search_from=request_start,
    )

    apply_signature = "    private suspend fun applyRemoteRoomState(state: com.nikhil.yt.together.TogetherRoomState) {"
    apply_start = text.find(apply_signature)
    if apply_start < 0:
        raise RuntimeError("applyRemoteRoomState function not found")

    pending_start = "        val pending = togetherPendingGuestControl\n"
    last_state_marker = "        val lastSentAt = togetherRuntime.lastAppliedRoomStateSentAtElapsedMs\n"
    reconcile_replacement = """        val reconcileDecision = togetherGuestControl.reconcile(state, now)
        if (reconcileDecision.notifySongChangeFailure) {
            showTogetherNotice(getString(R.string.together_song_change_failed), key = \"GUEST_SEEK_TIMEOUT\")
        }
        if (!reconcileDecision.applyRemoteState) return

"""
    text = replace_between(
        text,
        pending_start,
        last_state_marker,
        reconcile_replacement,
        "replace remote room-state pending reconciliation",
        search_from=apply_start,
    )

    stop_reset = """        togetherLastSentControlAtElapsedMs = 0L
        togetherLastSentControlAction = null
        togetherPendingGuestControl = null
        togetherRuntime.stopConnections()
"""
    text = replace_once(
        text,
        stop_reset,
        "        togetherGuestControl.reset()\n        togetherRuntime.stopConnections()\n",
        "replace Together stop reset",
    )

    forbidden = (
        "TogetherPendingGuestControl",
        "togetherLastSentControlAtElapsedMs",
        "togetherLastSentControlAction",
        "togetherPendingGuestControl",
        "val lastAction = togetherLastSentControlAction",
        "val pending = togetherPendingGuestControl",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy guest-control bookkeeping remained: {leftovers}")

    required = (
        "import com.nikhil.yt.together.TogetherGuestControlCoordinator",
        "private val togetherGuestControl = TogetherGuestControlCoordinator()",
        "togetherGuestControl.registerOutgoing(action, now, togetherRuntime.isOnlineSession)",
        "togetherGuestControl.reconcile(state, now)",
        "togetherGuestControl.reset()",
        "togetherRuntime.stopConnections()",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required guest-control coordinator wiring missing: {missing}")

    if text.count("togetherGuestControl.reset()") != 3:
        raise RuntimeError(
            "expected exactly 3 guest-control resets (two disabled-control paths + stop), found "
            f"{text.count('togetherGuestControl.reset()')}"
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
        print("OK: MusicService matches Together guest-control extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
