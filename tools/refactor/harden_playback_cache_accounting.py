#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackCacheManager.kt")

HELPER_ANCHOR = '''internal fun configuredPlayerCacheLimitBytes(
    enabled: Boolean,
    maxSongCacheSizeMb: Int,
): Long? {
'''

HELPER = '''internal fun accountedCacheBytesAfterRemoval(
    totalBytes: Long,
    removedSizeBytes: Long,
    removalSucceeded: Boolean,
): Long {
    if (!removalSucceeded) return totalBytes.coerceAtLeast(0L)
    return (totalBytes - removedSizeBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
}

'''

OLD_LOOP = '''                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { cache.removeResource(candidate.key) }
                totalBytes -= removedSize
'''

NEW_LOOP = '''                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                val removalSucceeded =
                    runCatching { cache.removeResource(candidate.key) }.isSuccess
                totalBytes =
                    accountedCacheBytesAfterRemoval(
                        totalBytes = totalBytes,
                        removedSizeBytes = removedSize,
                        removalSucceeded = removalSucceeded,
                    )
'''


def validate_before(source: str) -> None:
    required = [
        HELPER_ANCHOR,
        OLD_LOOP,
        "private suspend fun trimToBytes(limitBytes: Long)",
        "cache.removeResource(candidate.key)",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing cache-accounting preconditions: " + ", ".join(missing))
    if "accountedCacheBytesAfterRemoval(" in source:
        raise SystemExit("cache accounting hardening already applied")


def transform(source: str) -> str:
    if source.count(HELPER_ANCHOR) != 1:
        raise ValueError("configured cache helper anchor must appear once")
    if source.count(OLD_LOOP) != 1:
        raise ValueError("old removal accounting block must appear once")

    source = source.replace(HELPER_ANCHOR, HELPER + HELPER_ANCHOR, 1)
    source = source.replace(OLD_LOOP, NEW_LOOP, 1)
    return source


def validate_after(source: str) -> None:
    required = [
        "internal fun accountedCacheBytesAfterRemoval(",
        "removalSucceeded = removalSucceeded",
        "runCatching { cache.removeResource(candidate.key) }.isSuccess",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing hardened cache-accounting markers: " + ", ".join(missing))
    if OLD_LOOP in source:
        raise SystemExit("old optimistic cache accounting still present")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = TARGET.read_text()
    validate_before(source)
    if args.check:
        print("Playback cache accounting hardening preconditions satisfied")
        return

    updated = transform(source)
    validate_after(updated)
    TARGET.write_text(updated)
    print("Playback cache accounting now advances only after successful eviction")


if __name__ == "__main__":
    main()
