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


def transform(text: str) -> str:
    if "CapsuleVideoStreamInterceptor" not in text:
        raise RuntimeError("Step 3 VIDEO ownership extraction must be applied first")
    if "DiscordPresenceOwner(" in text:
        raise RuntimeError("Discord presence ownership extraction is already applied")

    text = replace_once(
        text,
        "import com.nikhil.yt.utils.DiscordRPC\n",
        "",
        "remove duplicate DiscordRPC import",
    )
    text = replace_once(
        text,
        "import com.nikhil.yt.playback.audio.PlaybackDataCache\n",
        "import com.nikhil.yt.playback.audio.PlaybackDataCache\n"
        "import com.nikhil.yt.playback.presence.DiscordPresenceOwner\n",
        "import DiscordPresenceOwner",
    )

    text = replace_once(
        text,
        "    private var lastPresenceToken: String? = null\n"
        "    @Volatile\n"
        "    private var lastPresenceUpdateTime = 0L\n",
        "    private val discordPresenceOwner by lazy(LazyThreadSafetyMode.NONE) {\n"
        "        DiscordPresenceOwner(\n"
        "            context = this,\n"
        "            scopeProvider = { scope },\n"
        "            enabledProvider = { dataStore.get(EnableDiscordRPCKey, true) },\n"
        "            tokenProvider = { dataStore.get(DiscordTokenKey, \"\") },\n"
        "            songProvider = {\n"
        "                player.currentMetadata?.let { createTransientSongFromMedia(it) }\n"
        "                    ?: currentSong.value\n"
        "            },\n"
        "            positionProvider = { player.currentPosition },\n"
        "            isPausedProvider = { !player.isPlaying },\n"
        "            intervalProvider = { getPresenceIntervalMillis(this@MusicService) },\n"
        "            onFailure = { operation, error ->\n"
        "                Timber.tag(\"MusicService\").e(error, operation)\n"
        "            },\n"
        "        )\n"
        "    }\n"
        "    @Volatile\n"
        "    private var lastPresenceUpdateTime = 0L\n",
        "install Discord presence owner",
    )

    text = replace_once(
        text,
        "    private var discordRpc: DiscordRPC? = null\n"
        "    private var lastDiscordUpdateTime = 0L\n\n",
        "",
        "remove duplicate Discord client state",
    )

    text = replace_once(
        text,
        "        currentSong.debounce(300).collect(scope) { song ->\n"
        "            updateNotification()\n"
        "            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {\n"
        "                ensurePresenceManager()\n"
        "            } else {\n"
        "                discordRpc?.closeRPC()\n"
        "            }\n"
        "        }\n",
        "        currentSong.debounce(300).collect(scope) { song ->\n"
        "            updateNotification()\n"
        "            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {\n"
        "                discordPresenceOwner.ensure()\n"
        "            } else {\n"
        "                discordPresenceOwner.stop()\n"
        "            }\n"
        "        }\n",
        "route song lifecycle to presence owner",
    )

    old_settings_block = """        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                val newRpc =
                    withContext(Dispatchers.IO) {
                        if (!key.isNullOrBlank() && enabled) {
                            runCatching { DiscordRPC(this@MusicService, key) }
                                .onFailure { Timber.tag(\"MusicService\").e(it, \"failed to create DiscordRPC client\") }
                                .getOrNull()
                        } else {
                            null
                        }
                    }

                try {
                    if (discordRpc?.isRpcRunning() == true) {
                        withContext(Dispatchers.IO) { discordRpc?.closeRPC() }
                    }
                } catch (error: Exception) {
                    reportRecoverableException(\"MusicService\", \"close previous Discord RPC\", error)
                }
                discordRpc = newRpc

                if (discordRpc != null) {
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            ensurePresenceManager()
                        }
                    }
                } else {
                    try {
                        DiscordPresenceManager.stop()
                    } catch (error: Exception) {
                        reportRecoverableException(\"MusicService\", \"stop disabled Discord presence\", error)
                    }
                }
            }
"""
    new_settings_block = """        dataStore.data
            .map { it[DiscordTokenKey].orEmpty() to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                discordPresenceOwner.reconcile(
                    enabled = enabled,
                    configuredToken = key,
                )
            }
"""
    text = replace_once(
        text,
        old_settings_block,
        new_settings_block,
        "replace duplicate Discord client settings collector",
    )

    text = remove_function(text, "    private fun ensurePresenceManager()")

    text = text.replace("ensurePresenceManager()", "discordPresenceOwner.ensure()")
    text = text.replace("scope.launch { discordRpc?.stopActivity() }", "discordPresenceOwner.stop()")

    manual_transition_restart = """                                try {
                                    DiscordPresenceManager.stop()
                                    DiscordPresenceManager.start(
                                        this@MusicService,
                                        dataStore.get(DiscordTokenKey, \"\"),
                                        { song },
                                        { player.currentPosition },
                                        { !player.isPlaying },
                                        { getPresenceIntervalMillis(this@MusicService) },
                                    )
                                } catch (error: Exception) {
                                    reportRecoverableException(
                                        \"MusicService\",
                                        \"restart Discord presence after transition\",
                                        error,
                                    )
                                }
"""
    text = replace_once(
        text,
        manual_transition_restart,
        "                                discordPresenceOwner.restart()\n",
        "centralize transition restart",
    )

    stop_then_restart = """                                if (DiscordPresenceManager.isRunning()) {
                                    try {
                                        DiscordPresenceManager.stop()
                                        DiscordPresenceManager.restart()
                                    } catch (error: Exception) {
                                        reportRecoverableException(
                                            \"MusicService\",
                                            \"restart Discord presence after playback-state change\",
                                            error,
                                        )
                                    }
                                }
"""
    text = replace_once(
        text,
        stop_then_restart,
        "                                discordPresenceOwner.restart()\n",
        "centralize playback-state restart",
    )

    on_destroy_discord = """        try {
            DiscordPresenceManager.stop()
        } catch (error: Exception) {
            reportRecoverableException(\"MusicService\", \"stop Discord presence during destroy\", error)
        }
        try {
            discordRpc?.closeRPC()
        } catch (error: Exception) {
            reportRecoverableException(\"MusicService\", \"close Discord RPC during destroy\", error)
        }
        discordRpc = null
"""
    text = replace_once(
        text,
        on_destroy_discord,
        "        discordPresenceOwner.stop()\n",
        "centralize destroy cleanup",
    )

    task_removed_discord = """        try {
            scope.launch {
                try {
                    discordRpc?.stopActivity()
                } catch (error: Exception) {
                    reportRecoverableException(\"MusicService\", \"stop Discord activity after task removal\", error)
                }
            }
        } catch (error: Exception) {
            reportRecoverableException(\"MusicService\", \"schedule Discord activity stop\", error)
        }

        try {
            if (discordRpc?.isRpcRunning() == true) {
                try {
                    discordRpc?.closeRPC()
                } catch (error: Exception) {
                    reportRecoverableException(\"MusicService\", \"close Discord RPC after task removal\", error)
                }
            }
        } catch (error: Exception) {
            reportRecoverableException(\"MusicService\", \"inspect Discord RPC after task removal\", error)
        }
        discordRpc = null
        try {
            DiscordPresenceManager.stop()
        } catch (error: Exception) {
            reportRecoverableException(\"MusicService\", \"stop Discord presence after task removal\", error)
        }
        lastPresenceToken = null
"""
    text = replace_once(
        text,
        task_removed_discord,
        "        discordPresenceOwner.stop()\n",
        "centralize task-removal cleanup",
    )

    forbidden = (
        "import com.nikhil.yt.utils.DiscordRPC",
        "private var discordRpc",
        "discordRpc?",
        "lastDiscordUpdateTime",
        "lastPresenceToken",
        "private fun ensurePresenceManager",
        "DiscordPresenceManager.start(",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"legacy Discord ownership remained: {leftovers}")

    required = (
        "import com.nikhil.yt.playback.presence.DiscordPresenceOwner",
        "private val discordPresenceOwner by lazy",
        "discordPresenceOwner.reconcile(",
        "discordPresenceOwner.ensure()",
        "discordPresenceOwner.restart()",
        "discordPresenceOwner.stop()",
        "DiscordPresenceManager.updateNow(",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required Discord ownership wiring missing: {missing}")

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
        print("OK: MusicService matches Discord presence-owner extraction preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
