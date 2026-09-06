#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")


def matching_brace(text: str, open_index: int) -> int:
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
            if ch == "\n": line_comment = False
            i += 1
            continue
        if block_comment:
            if ch == "/" and nxt == "*": block_comment += 1; i += 2; continue
            if ch == "*" and nxt == "/": block_comment -= 1; i += 2; continue
            i += 1
            continue
        if in_string:
            if escaped: escaped = False
            elif ch == "\\": escaped = True
            elif ch == '"': in_string = False
            i += 1
            continue
        if ch == "/" and nxt == "/": line_comment = True; i += 2; continue
        if ch == "/" and nxt == "*": block_comment = 1; i += 2; continue
        if ch == '"': in_string = True; i += 1; continue
        if ch == "{": depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0: return i
        i += 1
    raise ValueError("unmatched brace")


def remove_function(source: str, signature: str) -> str:
    start = source.find(signature)
    if start < 0 or source.find(signature, start + len(signature)) >= 0:
        raise ValueError(f"function match invalid: {signature.strip()}")
    opening = source.find("{", start)
    end = matching_brace(source, opening)
    return source[:start] + source[end + 1:]


def once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected one match, found {count}")
    return source.replace(old, new, 1)


def transform(source: str) -> str:
    source = once(
        source,
        "    private val songRecoveryJobs = ConcurrentHashMap<String, Job>()\n"
        "    private val songRecoveryLock = Any()\n"
        "    private val audioResolveStability = PlaybackStabilityGate()\n",
        "    private val audioResolveStability = PlaybackStabilityGate()\n",
        "metadata job fields",
    )

    source = once(
        source,
        "    private val automixRuntime = AutomixRuntime()\n",
        '''    private val automixRuntime = AutomixRuntime()
    private val songMetadataRecoveryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        SongMetadataRecoveryCoordinator(
            scopeProvider = { ioScope },
            database = database,
            awaitStable = { mediaId ->
                audioResolveStability.awaitStable {
                    withContext(Dispatchers.Main.immediate) {
                        player.currentMediaItem?.mediaId == mediaId
                    }
                }
            },
            mediaMetadataProvider = { mediaId ->
                withContext(Dispatchers.Main.immediate) {
                    player.findNextMediaItemById(mediaId)?.metadata
                }
            },
            automixJobProvider = { mediaId ->
                withContext(Dispatchers.Main.immediate) {
                    automixRuntime.jobForSeed(mediaId)
                }
            },
            playbackBlockedExceptionOrNull = {
                CapsuleAudioEngine.playbackBlockedExceptionOrNull()
            },
            onFailure = { mediaId, failure ->
                reportRecoverableException(
                    "MusicService",
                    "recover song metadata id=$mediaId",
                    failure,
                )
            },
        )
    }
''',
        "metadata coordinator field",
    )

    source = once(
        source,
        "            cacheRelatedSongs = { mediaId, songs -> cacheRelatedSongs(mediaId, songs) },\n",
        '''            cacheRelatedSongs = { mediaId, songs ->
                songMetadataRecoveryCoordinator.cacheRelatedSongs(mediaId, songs)
            },
''',
        "automix related-song callback",
    )

    source = remove_function(source, "    private fun scheduleSongRecovery(")
    source = remove_function(source, "    private suspend fun cacheRelatedSongs(")
    source = remove_function(source, "    private suspend fun recoverSong(")
    source = source.replace(
        "scheduleSongRecovery(",
        "songMetadataRecoveryCoordinator.schedule(",
    )

    source = once(
        source,
        '''        songRecoveryJobs.forEach { (id, job) ->
            if (id != mediaItem?.mediaId && songRecoveryJobs.remove(id, job)) job.cancel()
        }
''',
        "        songMetadataRecoveryCoordinator.cancelExcept(mediaItem?.mediaId)\n",
        "stale metadata cancellation",
    )

    source = source.replace("import com.nikhil.yt.db.entities.RelatedSongMap\n", "")
    source = source.replace("import com.nikhil.yt.innertube.models.SongItem\n", "")
    return source


def validate_before(source: str) -> None:
    required = [
        "private val songRecoveryJobs = ConcurrentHashMap<String, Job>()",
        "private val songRecoveryLock = Any()",
        "private fun scheduleSongRecovery(",
        "scheduleSongRecovery(mediaId)",
        "scheduleSongRecovery(mediaId, cached)",
        "scheduleSongRecovery(mediaId, playbackData)",
        "private suspend fun cacheRelatedSongs(",
        "private suspend fun recoverSong(",
        "YouTube.next(WatchEndpoint(videoId = mediaId))",
        "YouTube.related(relatedEndpoint)",
        "cacheRelatedSongs = { mediaId, songs -> cacheRelatedSongs(mediaId, songs) }",
        "private fun audioResolveJob(",
        ".resolvePlayback(",
        "ResolvingDataSource(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing metadata-recovery preconditions: " + ", ".join(missing))
    if "songMetadataRecoveryCoordinator by lazy" in source:
        raise SystemExit("SongMetadataRecoveryCoordinator already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val songMetadataRecoveryCoordinator by lazy",
        "songMetadataRecoveryCoordinator.schedule(mediaId)",
        "songMetadataRecoveryCoordinator.schedule(mediaId, cached)",
        "songMetadataRecoveryCoordinator.schedule(mediaId, playbackData)",
        "songMetadataRecoveryCoordinator.cancelExcept(mediaItem?.mediaId)",
        "songMetadataRecoveryCoordinator.cacheRelatedSongs(mediaId, songs)",
        "private fun audioResolveJob(",
        ".resolvePlayback(",
        "ResolvingDataSource(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed metadata markers: " + ", ".join(missing))

    forbidden = [
        "songRecoveryJobs",
        "songRecoveryLock",
        "scheduleSongRecovery(",
        "private suspend fun cacheRelatedSongs(",
        "private suspend fun recoverSong(",
        "YouTube.next(",
        "YouTube.related(",
        "import com.nikhil.yt.db.entities.RelatedSongMap",
        "import com.nikhil.yt.innertube.models.SongItem",
        "songMetadataRecoveryCoordinator::cacheRelatedSongs",
    ]
    leaked = [item for item in forbidden if item in source]
    if leaked:
        raise SystemExit("metadata recovery leaked into MusicService: " + ", ".join(leaked))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Song metadata recovery extraction preconditions satisfied")
        return
    updated = transform(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Song metadata recovery moved behind SongMetadataRecoveryCoordinator")


if __name__ == "__main__":
    main()
