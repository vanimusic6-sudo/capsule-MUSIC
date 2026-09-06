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


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected exactly one match, found {count}")
    return source.replace(old, new, 1)


def transformed(source: str) -> str:
    player_cache_field = '''    @Inject
    @PlayerCache
    lateinit var playerCache: Cache
'''
    player_cache_replacement = '''    @Inject
    @PlayerCache
    lateinit var playerCache: Cache
    private val playbackCacheManager by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackCacheManager(
            cache = playerCache,
            cacheDirectory = filesDir.resolve("exoplayer"),
        )
    }
'''
    source = replace_once(
        source,
        player_cache_field,
        player_cache_replacement,
        "player cache field",
    )

    smart_trimmer = '''        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }
'''
    smart_trimmer_replacement = '''        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                playbackCacheManager.trimToConfiguredLimit(
                    enabled = enabled,
                    maxSongCacheSizeMb = maxSongCacheSizeMb,
                )
            }
'''
    source = replace_once(source, smart_trimmer, smart_trimmer_replacement, "smart-trimmer collector")

    source = replace_function(source, "    private suspend fun trimPlayerCacheToBytes(limitBytes: Long) {", "")

    import_line = "import com.nikhil.yt.extensions.directorySizeBytes\n"
    source = replace_once(source, import_line, "", "directorySizeBytes import")
    return source


def validate_before(source: str) -> None:
    required = [
        "lateinit var playerCache: Cache",
        "private suspend fun trimPlayerCacheToBytes(limitBytes: Long)",
        "trimPlayerCacheToBytes(limitBytes)",
        "import com.nikhil.yt.extensions.directorySizeBytes",
        "private fun createCacheDataSource(): DataSource.Factory",
        "private fun createVideoCacheDataSource(): CacheDataSource.Factory",
        "private fun createDataSourceFactory(): DataSource.Factory",
        "ResolvingDataSource(",
        "AudioCacheIdentity.",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing cache-manager preconditions: " + ", ".join(missing))
    if "private val playbackCacheManager by lazy" in source:
        raise SystemExit("PlaybackCacheManager already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val playbackCacheManager by lazy",
        "PlaybackCacheManager(",
        "playbackCacheManager.trimToConfiguredLimit(",
        "private fun createCacheDataSource(): DataSource.Factory",
        "private fun createVideoCacheDataSource(): CacheDataSource.Factory",
        "private fun createDataSourceFactory(): DataSource.Factory",
        "ResolvingDataSource(",
        "AudioCacheIdentity.",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed cache markers: " + ", ".join(missing))

    forbidden = [
        "private suspend fun trimPlayerCacheToBytes(limitBytes: Long)",
        "trimPlayerCacheToBytes(limitBytes)",
        "import com.nikhil.yt.extensions.directorySizeBytes",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("cache trimming leaked into MusicService: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Playback cache manager extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Smart Trimmer ownership moved behind PlaybackCacheManager")


if __name__ == "__main__":
    main()
