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


def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise ValueError(f"missing function signature: {signature}")
    open_index = text.find("{", start)
    if open_index < 0:
        raise ValueError(f"missing opening brace: {signature}")
    end = find_matching_brace(text, open_index)
    return text[:start] + replacement.rstrip() + text[end + 1 :]


def replace_playback_ended_automix(text: str) -> str:
    method_sig = "    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {"
    method_start = text.find(method_sig)
    if method_start < 0:
        raise ValueError("onPlaybackStateChanged not found")
    method_open = text.find("{", method_start)
    method_end = find_matching_brace(text, method_open)
    method = text[method_start : method_end + 1]

    marker = "    if (!suppressAutoPlayback &&\n        playbackState == Player.STATE_ENDED &&"
    block_start = method.find(marker)
    if block_start < 0:
        raise ValueError("playback-ended Automix block not found")
    open_index = method.find("{", block_start)
    block_end = find_matching_brace(method, open_index)

    replacement = '''    if (!suppressAutoPlayback &&
        playbackState == Player.STATE_ENDED &&
        dataStore.get(AutoLoadMoreKey, true) &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.currentMediaItem != null
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val lastMediaMetadata = player.currentMetadata
            val existingAutomix = automixItems.value
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId != lastMediaMetadata?.id }
                if (filteredAutomix.isNotEmpty()) {
                    automixCoordinator.clearOwnedIds()
                    player.setMediaItems(filteredAutomix, 0, 0)
                    player.prepare()
                    player.play()
                    automixCoordinator.markAutoAdded(filteredAutomix)
                }
                clearAutomix()
            } else if (lastMediaMetadata != null) {
                val hideExplicit = dataStore.get(HideExplicitKey, false)
                val hideVideo = dataStore.get(HideVideoKey, false)
                automixCoordinator.recoverAfterQueueEnded(
                    seedMediaId = lastMediaMetadata.id,
                    hideExplicit = hideExplicit,
                    hideVideo = hideVideo,
                    isBeforeApplyRelevant = {
                        !suppressAutoPlayback && player.playbackState != STATE_IDLE && player.mediaItemCount > 0
                    },
                    isAfterApplyRelevant = {
                        !suppressAutoPlayback && player.playbackState != STATE_IDLE
                    },
                    onReplaceQueue = { radioItems ->
                        player.setMediaItems(radioItems, 0, 0)
                        player.prepare()
                        player.play()
                    },
                    noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
                    failureMessage = { getString(R.string.error_automix_failed) },
                )
            }
        }
    }'''

    updated_method = method[:block_start] + replacement + method[block_end + 1 :]
    return text[:method_start] + updated_method + text[method_end + 1 :]


def transformed(source: str) -> str:
    owner_old = '''    private val automixRuntime = AutomixRuntime()
    val automixItems = automixRuntime.items
    val automixLoading = automixRuntime.loading
    val automixError = automixRuntime.error
    val autoAddedMediaIds = automixRuntime.autoAddedMediaIds
'''
    owner_new = '''    private val automixRuntime = AutomixRuntime()
    private val automixCoordinator =
        AutomixCoordinator(
            runtime = automixRuntime,
            scopeProvider = { scope },
            stabilityGate = audioResolveStability,
            cacheRelatedSongs = { mediaId, songs -> cacheRelatedSongs(mediaId, songs) },
            playbackBlockedExceptionOrNull = { CapsuleAudioEngine.playbackBlockedExceptionOrNull() },
        )
    val automixItems = automixCoordinator.items
    val automixLoading = automixCoordinator.loading
    val automixError = automixCoordinator.error
    val autoAddedMediaIds = automixCoordinator.autoAddedMediaIds
'''
    if owner_old not in source:
        raise ValueError("Automix owner block does not match expected source")
    source = source.replace(owner_old, owner_new, 1)

    source = replace_function(
        source,
        "    fun getAutomixAlbum(albumId: String) {",
        '''    fun getAutomixAlbum(albumId: String) {
        if (!dataStore.get(AutoLoadMoreKey, true) || player.repeatMode != REPEAT_MODE_OFF) return
        val seedAtRequest = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
        automixCoordinator.loadAlbum(
            albumId = albumId,
            expectedSeedMediaId = seedAtRequest,
            currentSeedProvider = {
                player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
            },
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun getAutomix(playlistId: String) {",
        '''    fun getAutomix(playlistId: String) {
        if (!dataStore.get(AutoLoadMoreKey, true) || player.repeatMode != REPEAT_MODE_OFF) return
        val seedAtRequest = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
        automixCoordinator.loadPlaylist(
            playlistId = playlistId,
            expectedSeedMediaId = seedAtRequest,
            currentSeedProvider = {
                player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
            },
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun addToQueueAutomix(",
        '''    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixCoordinator.removeAt(position)
        addToQueue(listOf(item))
    }''',
    )
    source = replace_function(
        source,
        "    fun playNextAutomix(",
        '''    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixCoordinator.removeAt(position)
        playNext(listOf(item))
    }''',
    )
    source = replace_function(
        source,
        "    fun clearAutomix() {",
        '''    fun clearAutomix() {
        automixCoordinator.clear()
    }''',
    )
    source = replace_function(
        source,
        "    private fun refreshAutomixForCurrentMedia() {",
        '''    private fun refreshAutomixForCurrentMedia() {
        if (!dataStore.get(AutoLoadMoreKey, true)) return
        if (player.repeatMode != REPEAT_MODE_OFF) return
        if (suppressAutoPlayback) return
        if (player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return

        val seedMediaId = player.currentMetadata?.id?.trim()?.ifBlank { null } ?: return
        automixCoordinator.refresh(
            seedMediaId = seedMediaId,
            hideExplicit = dataStore.get(HideExplicitKey, false),
            hideVideo = dataStore.get(HideVideoKey, false),
            queueIdsProvider = {
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            },
            isRelevant = { seed ->
                player.currentMediaItem?.mediaId == seed &&
                    !suppressAutoPlayback &&
                    player.playbackState != STATE_IDLE &&
                    player.mediaItemCount > 0
            },
            noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
            failureMessage = { getString(R.string.error_automix_failed) },
        )
    }''',
    )
    source = replace_function(
        source,
        "    fun onInfiniteQueueDisabled() {",
        '''    fun onInfiniteQueueDisabled() {
        automixCoordinator.cancelTransientWork()
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = automixCoordinator.ownedIdsSnapshot()
        if (idsToRemove.isNotEmpty()) {
            for (i in player.mediaItemCount - 1 downTo 0) {
                if (i == currentIndex) continue
                if (player.getMediaItemAt(i).mediaId in idsToRemove) {
                    player.removeMediaItem(i)
                }
            }
        }
        automixCoordinator.clearOwnedIds()
        clearAutomix()
    }''',
    )
    source = replace_function(
        source,
        "    fun onInfiniteQueueEnabled() {",
        '''    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata
        if (currentMeta == null) {
            automixError.value = getString(R.string.error_no_song_playing)
            return
        }

        val seedMediaId = currentMeta.id.trim().ifBlank { return }
        automixCoordinator.expandNow(
            seedMediaId = seedMediaId,
            hideExplicit = dataStore.get(HideExplicitKey, false),
            hideVideo = dataStore.get(HideVideoKey, false),
            queueIdsProvider = {
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            },
            isRelevant = { seed ->
                !suppressAutoPlayback &&
                    player.playbackState != STATE_IDLE &&
                    player.mediaItemCount > 0 &&
                    automixRuntime.seedMediaId == seed
            },
            onAddItems = { player.addMediaItems(it) },
            noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
            failureMessage = { getString(R.string.error_automix_failed) },
        )
    }''',
    )

    source = replace_playback_ended_automix(source)
    return source


def validate_before(source: str) -> None:
    required = [
        "private val automixRuntime = AutomixRuntime()",
        "private fun refreshAutomixForCurrentMedia()",
        "fun onInfiniteQueueEnabled()",
        "YouTube.next(WatchEndpoint(videoId = seedMediaId))",
        "YouTube.next(WatchEndpoint(videoId = lastMediaMetadata.id))",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing preconditions: " + ", ".join(missing))
    if "private val automixCoordinator" in source:
        raise SystemExit("AutomixCoordinator already integrated")


def validate_after(source: str) -> None:
    required = [
        "private val automixCoordinator =",
        "automixCoordinator.refresh(",
        "automixCoordinator.expandNow(",
        "automixCoordinator.recoverAfterQueueEnded(",
        "automixCoordinator.loadPlaylist(",
        "automixCoordinator.loadAlbum(",
    ]
    missing = [item for item in required if item not in source]
    if missing:
        raise SystemExit("missing transformed markers: " + ", ".join(missing))
    # One YouTube.next remains intentionally in metadata recovery; Automix no
    # longer owns any direct next request inside MusicService.
    if source.count("YouTube.next(") != 1:
        raise SystemExit(f"expected exactly one non-Automix YouTube.next in MusicService, found {source.count('YouTube.next(')}")
    forbidden = [
        "YouTube.next(WatchEndpoint(videoId = seedMediaId))",
        "YouTube.next(WatchEndpoint(videoId = currentMeta.id))",
        "YouTube.next(WatchEndpoint(videoId = lastMediaMetadata.id))",
    ]
    present = [item for item in forbidden if item in source]
    if present:
        raise SystemExit("direct Automix requests remain: " + ", ".join(present))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = SERVICE.read_text()
    validate_before(source)
    if args.check:
        print("Automix coordinator extraction preconditions satisfied")
        return

    updated = transformed(source)
    validate_after(updated)
    SERVICE.write_text(updated)
    print("Automix request policy moved behind AutomixCoordinator")


if __name__ == "__main__":
    main()
