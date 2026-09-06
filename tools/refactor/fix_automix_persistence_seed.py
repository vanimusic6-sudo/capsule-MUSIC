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


def transform(text: str) -> str:
    if "private val automixRuntime = AutomixRuntime()" not in text:
        raise RuntimeError("Step 10 Automix runtime extraction must be applied first")
    if "automixSeedMediaId = automixRuntime.seedMediaId" in text:
        raise RuntimeError("Automix persistence seed fix is already applied")

    old_restore = """                playbackPersistence.read(PERSISTENT_QUEUE_FILE, PersistQueue::class.java)
                    ?.let { persistedQueue ->
                    val restoredQueue = persistedQueue.toQueue()
                    withContext(Dispatchers.Main) {
                        playQueue(
                            queue = restoredQueue,
                            playWhenReady = false,
                        )
                    }
                }
                playbackPersistence.read(PERSISTENT_AUTOMIX_FILE, PersistQueue::class.java)
                    ?.let { persistedAutomix ->
                    val items = persistedAutomix.items.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        automixItems.value = items
                        automixRuntime.seedMediaId = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                    }
                }
"""
    new_restore = """                var restoredQueueSeedMediaId: String? = null
                playbackPersistence.read(PERSISTENT_QUEUE_FILE, PersistQueue::class.java)
                    ?.let { persistedQueue ->
                    restoredQueueSeedMediaId =
                        persistedQueue.items
                            .getOrNull(persistedQueue.mediaItemIndex)
                            ?.id
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    val restoredQueue = persistedQueue.toQueue()
                    withContext(Dispatchers.Main) {
                        playQueue(
                            queue = restoredQueue,
                            playWhenReady = false,
                        )
                    }
                }
                playbackPersistence.read(PERSISTENT_AUTOMIX_FILE, PersistQueue::class.java)
                    ?.let { persistedAutomix ->
                    val items = persistedAutomix.items.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        automixRuntime.restore(
                            restoredItems = items,
                            persistedSeedMediaId = persistedAutomix.automixSeedMediaId,
                            fallbackSeedMediaId = restoredQueueSeedMediaId,
                            restoredAutoAddedMediaIds = persistedAutomix.automixAutoAddedMediaIds,
                        )
                    }
                }
"""
    text = replace_once(text, old_restore, new_restore, "replace Automix restore ownership")

    old_snapshot = """        val currentMediaItemIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
        val playerState = capturePersistentPlayerState() ?: return null
"""
    new_snapshot = """        val currentMediaItemIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
        val automixAutoAddedSnapshot =
            synchronized(autoAddedMediaIds) { autoAddedMediaIds.toList() }
        val playerState = capturePersistentPlayerState() ?: return null
"""
    text = replace_once(text, old_snapshot, new_snapshot, "capture Automix queue ownership")

    old_automix_persist = """            automix =
                PersistQueue(
                    title = \"automix\",
                    items = automixSnapshot,
                    mediaItemIndex = 0,
                    position = 0,
                ),
"""
    new_automix_persist = """            automix =
                PersistQueue(
                    title = \"automix\",
                    items = automixSnapshot,
                    mediaItemIndex = 0,
                    position = 0,
                    automixSeedMediaId =
                        automixRuntime.seedMediaId
                            ?.trim()
                            ?.takeIf { it.isNotBlank() },
                    automixAutoAddedMediaIds = automixAutoAddedSnapshot,
                ),
"""
    text = replace_once(text, old_automix_persist, new_automix_persist, "persist Automix ownership metadata")

    forbidden = (
        "automixRuntime.seedMediaId = player.currentMetadata?.id",
    )
    leftovers = [token for token in forbidden if token in text]
    if leftovers:
        raise RuntimeError(f"timing-dependent Automix restore remained: {leftovers}")

    required = (
        "var restoredQueueSeedMediaId: String? = null",
        ".getOrNull(persistedQueue.mediaItemIndex)",
        "automixRuntime.restore(",
        "persistedSeedMediaId = persistedAutomix.automixSeedMediaId",
        "fallbackSeedMediaId = restoredQueueSeedMediaId",
        "restoredAutoAddedMediaIds = persistedAutomix.automixAutoAddedMediaIds",
        "val automixAutoAddedSnapshot =",
        "automixSeedMediaId =",
        "automixRuntime.seedMediaId",
        "automixAutoAddedMediaIds = automixAutoAddedSnapshot",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise RuntimeError(f"required Automix persistence wiring missing: {missing}")

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
        print("OK: MusicService matches Automix persistence-fix preconditions")
        return

    path.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Updated {path}")


if __name__ == "__main__":
    main()
