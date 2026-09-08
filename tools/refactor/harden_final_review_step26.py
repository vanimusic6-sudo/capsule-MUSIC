#!/usr/bin/env python3
"""Apply the final field-review hardening pass for Capsule MUSIC.

The script is intentionally deterministic: every replacement is anchored to the
validated step-25 tree and refuses to continue when the branch shape differs.
"""
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def assert_absent(text: str, marker: str, label: str) -> None:
    if marker in text:
        raise SystemExit(f"{label}: final marker already present")


def patch_safety(check_only: bool) -> None:
    rel = "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsulePlaybackSafety.kt"
    text = read(rel)
    assert_absent(text, "fun remainingBlockMs(", rel)
    old = """    @Synchronized\n    fun blockedExceptionOrNull(nowMs: Long = System.currentTimeMillis()): PlaybackException? {\n"""
    new = """    @Synchronized\n    fun remainingBlockMs(nowMs: Long = System.currentTimeMillis()): Long {\n        val until = breakerUntilMs\n        if (until <= 0L) return 0L\n        if (until <= nowMs) {\n            clear()\n            return 0L\n        }\n        return until - nowMs\n    }\n\n    @Synchronized\n    fun blockedExceptionOrNull(nowMs: Long = System.currentTimeMillis()): PlaybackException? {\n"""
    text = replace_once(text, old, new, rel)
    if not check_only:
        write(rel, text)

    test_rel = "app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsulePlaybackSafetyTest.kt"
    test = read(test_rel)
    assert_absent(test, "remainingCooldownReportsOpenBreaker", test_rel)
    anchor = """    @Test\n    fun transport429TextAlsoOpensCooldown() {\n        CapsulePlaybackSafety.observeFailure(IllegalStateException(\"player request failed: HTTP 429\"))\n        assertNotNull(CapsulePlaybackSafety.blockedExceptionOrNull())\n    }\n"""
    replacement = anchor + """\n    @Test\n    fun remainingCooldownReportsOpenBreaker() {\n        val beforeTrip = System.currentTimeMillis()\n        CapsulePlaybackSafety.markHttpStatusFailure(429)\n\n        val remainingMs = CapsulePlaybackSafety.remainingBlockMs(beforeTrip)\n\n        assertTrue(remainingMs >= 9 * 60 * 1000L)\n        assertTrue(remainingMs <= 10 * 60 * 1000L + 1_000L)\n    }\n"""
    test = replace_once(test, anchor, replacement, test_rel)
    if not check_only:
        write(test_rel, test)


def patch_downloads(check_only: bool) -> None:
    rel = "app/src/main/kotlin/com/nikhil/yt/playback/DownloadUtil.kt"
    text = read(rel)
    assert_absent(text, "DOWNLOAD_RESOLVE_SPACING_MS", rel)

    text = replace_once(
        text,
        "import com.nikhil.yt.playback.audio.CapsuleAudioEngine\n",
        "import com.nikhil.yt.playback.audio.CapsuleAudioEngine\nimport com.nikhil.yt.playback.audio.CapsulePlaybackSafety\n",
        rel,
    )
    text = replace_once(
        text,
        "import kotlinx.coroutines.launch\n",
        "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\n",
        rel,
    )
    text = replace_once(
        text,
        "    private val consecutiveThrottleSignals = AtomicInteger(0)\n",
        "    private val consecutiveThrottleSignals = AtomicInteger(0)\n    private val downloadResolveMutex = Mutex()\n    @Volatile private var lastDownloadResolveStartMs = 0L\n",
        rel,
    )

    old_resolve = """            resolve = {\n                val remainingMs = cooldownUntilMs - System.currentTimeMillis()\n                if (remainingMs > 0) delay(remainingMs)\n                val selection = playbackContext()\n                val playbackData = CapsuleAudioEngine.playerResponseForPlayback(\n                    videoId = request.id,\n                    audioQuality = selection.quality,\n                    connectivityManager = connectivityManager,\n                    streamPolicy = selection.policy,\n                    priority = AudioResolvePriority.DOWNLOAD,\n                ).getOrThrow()\n                if (selection != playbackContext()) throw java.io.IOException(\"Playback context changed during download resolve\")\n                storeDownloadMetadata(request.id, playbackData)\n                playbackData\n            },\n"""
    text = replace_once(text, old_resolve, "            resolve = { resolveDownloadPlayback(request.id) },\n", rel)

    anchor = "    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())\n\n"
    methods = """    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())\n\n    private suspend fun awaitDownloadResolveWindow() {\n        downloadResolveMutex.withLock {\n            while (true) {\n                val nowMs = System.currentTimeMillis()\n                val waitMs =\n                    maxOf(\n                        CapsulePlaybackSafety.remainingBlockMs(nowMs),\n                        (cooldownUntilMs - nowMs).coerceAtLeast(0L),\n                        (lastDownloadResolveStartMs + DOWNLOAD_RESOLVE_SPACING_MS - nowMs)\n                            .coerceAtLeast(0L),\n                    )\n\n                if (waitMs <= 0L) {\n                    lastDownloadResolveStartMs = System.currentTimeMillis()\n                    return@withLock\n                }\n\n                // Re-check periodically so a network change / explicit breaker reset\n                // can resume the queue without waiting for the old full deadline.\n                delay(minOf(waitMs, DOWNLOAD_WAIT_SLICE_MS))\n            }\n        }\n    }\n\n    private suspend fun resolveDownloadPlayback(\n        mediaId: String,\n    ): CapsuleAudioEngine.PlaybackData {\n        while (true) {\n            awaitDownloadResolveWindow()\n            val selection = playbackContext()\n            val result =\n                CapsuleAudioEngine.playerResponseForPlayback(\n                    videoId = mediaId,\n                    audioQuality = selection.quality,\n                    connectivityManager = connectivityManager,\n                    streamPolicy = selection.policy,\n                    priority = AudioResolvePriority.DOWNLOAD,\n                )\n\n            if (selection != playbackContext()) {\n                throw java.io.IOException(\"Playback context changed during download resolve\")\n            }\n\n            result.getOrNull()?.let { playbackData ->\n                storeDownloadMetadata(mediaId, playbackData)\n                return playbackData\n            }\n\n            val failure =\n                result.exceptionOrNull()\n                    ?: java.io.IOException(\"Download audio resolve failed without an exception\")\n\n            // A global 429/bot-check breaker is a queue pause, not a reason to\n            // permanently fail the user's download. The next iteration waits at\n            // the shared gate and retries only after the breaker becomes safe.\n            if (CapsulePlaybackSafety.remainingBlockMs() > 0L) {\n                registerThrottleSignal(failure)\n                continue\n            }\n\n            throw failure\n        }\n    }\n\n"""
    text = replace_once(text, anchor, methods, rel)

    text = replace_once(
        text,
        """            \"reset by peer\",\n        ).any(message::contains)\n""",
        """            \"reset by peer\",\n            \"no playable clients\",\n            \"no playable audio stream\",\n            \"client response unavailable\",\n        ).any(message::contains)\n""",
        rel,
    )
    text = replace_once(
        text,
        """        private const val LONG_COOLDOWN_MS = 8_000L\n""",
        """        private const val LONG_COOLDOWN_MS = 8_000L\n        private const val DOWNLOAD_RESOLVE_SPACING_MS = 4_000L\n        private const val DOWNLOAD_WAIT_SLICE_MS = 2_000L\n""",
        rel,
    )
    if not check_only:
        write(rel, text)


def patch_sync(check_only: bool) -> None:
    rel = "app/src/main/kotlin/com/nikhil/yt/utils/SyncUtils.kt"
    text = read(rel)
    assert_absent(text, "AUTO_SYNC_MIN_INTERVAL_MS", rel)

    text = replace_once(
        text,
        "import kotlinx.coroutines.withContext\n",
        "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.withTimeoutOrNull\n",
        rel,
    )
    text = replace_once(
        text,
        "    private val isSyncing = AtomicBoolean(false)\n",
        """    private val isSyncing = AtomicBoolean(false)\n    private val automaticSyncTimes = mutableMapOf<String, Long>()\n    private val automaticSyncLock = Any()\n""",
        rel,
    )

    old_login = """    private suspend fun isLoggedIn(): Boolean {\n        val cookie = context.dataStore.data\n            .map { it[InnerTubeCookieKey] }\n            .first()\n        return cookie?.let { \"SAPISID\" in parseCookieString(it) } ?: false\n    }\n"""
    new_login = """    private suspend fun isLoggedIn(): Boolean {\n        val cookie =\n            context.dataStore.data\n                .map { it[InnerTubeCookieKey] }\n                .first()\n                ?.trim()\n                .orEmpty()\n\n        if (cookie.isBlank() || \"SAPISID\" !in parseCookieString(cookie)) return false\n\n        if (YouTube.authState.cookie == cookie && YouTube.authState.hasLoginCookie) return true\n\n        val published =\n            withTimeoutOrNull(AUTH_PUBLICATION_TIMEOUT_MS) {\n                YouTube.authStates.first { state ->\n                    state.cookie == cookie && state.hasLoginCookie\n                }\n            } != null\n\n        if (!published) {\n            // DataStore already committed this exact cookie. This only heals a\n            // stalled application collector; it never invents or replaces auth.\n            YouTube.cookie = cookie\n        }\n\n        return YouTube.authState.cookie == cookie && YouTube.authState.hasLoginCookie\n    }\n"""
    text = replace_once(text, old_login, new_login, rel)

    gate_anchor = """    private fun isSyncStillEnabled(gen: Long): Boolean {\n        return syncEnabled.value && syncGeneration.get() == gen\n    }\n"""
    gate_new = gate_anchor + """\n    private fun claimAutomaticSync(key: String): Boolean =\n        synchronized(automaticSyncLock) {\n            val nowMs = System.currentTimeMillis()\n            val lastMs = automaticSyncTimes[key]\n            if (lastMs != null && nowMs - lastMs in 0 until AUTO_SYNC_MIN_INTERVAL_MS) {\n                false\n            } else {\n                automaticSyncTimes[key] = nowMs\n                true\n            }\n        }\n\n    private fun isExpectedPrivatePlaylistFailure(error: Throwable): Boolean {\n        val text =\n            generateSequence(error as Throwable?) { it?.cause }\n                .take(8)\n                .mapNotNull { it?.message }\n                .joinToString(\" \")\n                .uppercase()\n        return \"PLAYLIST_PRIVATE\" in text ||\n            \"PRIVATE PLAYLIST\" in text ||\n            \"PLAYLIST IS PRIVATE\" in text\n    }\n"""
    text = replace_once(text, gate_anchor, gate_new, rel)

    replacements = [
        ("suspend fun syncLikedSongs() = coroutineScope {", "suspend fun syncLikedSongs(automatic: Boolean = false) = coroutineScope {", "AUTO_SYNC_LIKED_SONGS", "syncLikedSongs"),
        ("suspend fun syncLibrarySongs() = coroutineScope {", "suspend fun syncLibrarySongs(automatic: Boolean = false) = coroutineScope {", "AUTO_SYNC_LIBRARY_SONGS", "syncLibrarySongs"),
        ("suspend fun syncLikedAlbums() = coroutineScope {", "suspend fun syncLikedAlbums(automatic: Boolean = false) = coroutineScope {", "AUTO_SYNC_LIKED_ALBUMS", "syncLikedAlbums"),
        ("suspend fun syncArtistsSubscriptions() = coroutineScope {", "suspend fun syncArtistsSubscriptions(automatic: Boolean = false) = coroutineScope {", "AUTO_SYNC_ARTISTS", "syncArtistsSubscriptions"),
    ]
    for old_sig, new_sig, key, name in replacements:
        text = replace_once(text, old_sig, new_sig, rel)
        enabled_block = f"""        if (!isYtmSyncEnabled()) {{\n            Timber.w(\"Skipping {name} - sync disabled\")\n            return@coroutineScope\n        }}\n        val gen = syncGeneration.get()\n"""
        gated_block = f"""        if (!isYtmSyncEnabled()) {{\n            Timber.w(\"Skipping {name} - sync disabled\")\n            return@coroutineScope\n        }}\n        if (automatic && !claimAutomaticSync({key})) {{\n            Timber.d(\"{name}: automatic refresh skipped inside cooldown\")\n            return@coroutineScope\n        }}\n        val gen = syncGeneration.get()\n"""
        text = replace_once(text, enabled_block, gated_block, rel)

    text = replace_once(
        text,
        "suspend fun syncSavedPlaylists() = playlistSyncMutex.withLock {",
        "suspend fun syncSavedPlaylists(automatic: Boolean = false) = playlistSyncMutex.withLock {",
        rel,
    )
    text = replace_once(
        text,
        """        if (!isYtmSyncEnabled()) {\n            Timber.w(\"Skipping syncSavedPlaylists - sync disabled\")\n            return@withLock\n        }\n        val gen = syncGeneration.get()\n""",
        """        if (!isYtmSyncEnabled()) {\n            Timber.w(\"Skipping syncSavedPlaylists - sync disabled\")\n            return@withLock\n        }\n        if (automatic && !claimAutomaticSync(AUTO_SYNC_SAVED_PLAYLISTS)) {\n            Timber.d(\"syncSavedPlaylists: automatic refresh skipped inside cooldown\")\n            return@withLock\n        }\n        val gen = syncGeneration.get()\n""",
        rel,
    )

    text = replace_once(
        text,
        "suspend fun syncAutoSyncPlaylists() = coroutineScope {",
        "suspend fun syncAutoSyncPlaylists(automatic: Boolean = false) = coroutineScope {",
        rel,
    )
    text = replace_once(
        text,
        """        if (!isYtmSyncEnabled()) {\n            Timber.w(\"Skipping syncAutoSyncPlaylists - sync disabled\")\n            return@coroutineScope\n        }\n        val gen = syncGeneration.get()\n""",
        """        if (!isYtmSyncEnabled()) {\n            Timber.w(\"Skipping syncAutoSyncPlaylists - sync disabled\")\n            return@coroutineScope\n        }\n        if (automatic && !claimAutomaticSync(AUTO_SYNC_AUTO_PLAYLISTS)) {\n            Timber.d(\"syncAutoSyncPlaylists: automatic refresh skipped inside cooldown\")\n            return@coroutineScope\n        }\n        val gen = syncGeneration.get()\n""",
        rel,
    )

    text = replace_once(
        text,
        """        }.onFailure { e ->\n            Timber.e(e, \"syncPlaylist: Failed to fetch playlist from YouTube\")\n        }\n    }\n}\n\ninternal fun likedSongTimestamp""",
        """        }.onFailure { e ->\n            if (isExpectedPrivatePlaylistFailure(e)) {\n                Timber.w(\"syncPlaylist: Skipping private/inaccessible playlist browseId=$browseId\")\n            } else {\n                Timber.e(e, \"syncPlaylist: Failed to fetch playlist from YouTube\")\n            }\n        }\n    }\n\n    private companion object {\n        const val AUTH_PUBLICATION_TIMEOUT_MS = 1_500L\n        const val AUTO_SYNC_MIN_INTERVAL_MS = 60_000L\n        const val AUTO_SYNC_LIKED_SONGS = \"liked-songs\"\n        const val AUTO_SYNC_LIBRARY_SONGS = \"library-songs\"\n        const val AUTO_SYNC_LIKED_ALBUMS = \"liked-albums\"\n        const val AUTO_SYNC_ARTISTS = \"artists\"\n        const val AUTO_SYNC_SAVED_PLAYLISTS = \"saved-playlists\"\n        const val AUTO_SYNC_AUTO_PLAYLISTS = \"auto-playlists\"\n    }\n}\n\ninternal fun likedSongTimestamp""",
        rel,
    )
    if not check_only:
        write(rel, text)


def patch_viewmodels(check_only: bool) -> None:
    rel = "app/src/main/kotlin/com/nikhil/yt/viewmodels/LibraryViewModels.kt"
    text = read(rel)
    assert_absent(text, "automatic: Boolean = false", rel)

    text = replace_once(text, "fun refresh(filter: SongFilter) {", "fun refresh(filter: SongFilter, automatic: Boolean = false) {", rel)
    text = replace_once(text, "SongFilter.LIKED -> syncUtils.syncLikedSongs()", "SongFilter.LIKED -> syncUtils.syncLikedSongs(automatic = automatic)", rel)
    text = replace_once(text, "SongFilter.LIBRARY -> syncUtils.syncLibrarySongs()", "SongFilter.LIBRARY -> syncUtils.syncLibrarySongs(automatic = automatic)", rel)
    text = replace_once(text, """    fun syncLikedSongs() {\n        refresh(SongFilter.LIKED)\n    }\n\n    fun syncLibrarySongs() {\n        refresh(SongFilter.LIBRARY)\n    }\n""", """    fun syncLikedSongs(automatic: Boolean = false) {\n        refresh(SongFilter.LIKED, automatic = automatic)\n    }\n\n    fun syncLibrarySongs(automatic: Boolean = false) {\n        refresh(SongFilter.LIBRARY, automatic = automatic)\n    }\n""", rel)

    text = replace_once(text, "fun refresh(filter: ArtistFilter) {", "fun refresh(filter: ArtistFilter, automatic: Boolean = false) {", rel)
    text = replace_once(text, "syncUtils.syncArtistsSubscriptions()", "syncUtils.syncArtistsSubscriptions(automatic = automatic)", rel)
    text = replace_once(text, """    fun sync() {\n        refresh(ArtistFilter.LIKED)\n    }\n""", """    fun sync(automatic: Boolean = false) {\n        refresh(ArtistFilter.LIKED, automatic = automatic)\n    }\n""", rel)

    text = replace_once(text, "fun refresh(filter: AlbumFilter) {", "fun refresh(filter: AlbumFilter, automatic: Boolean = false) {", rel)
    text = replace_once(text, "syncUtils.syncLikedAlbums()", "syncUtils.syncLikedAlbums(automatic = automatic)", rel)
    text = replace_once(text, """    fun sync() {\n        refresh(AlbumFilter.LIKED)\n    }\n""", """    fun sync(automatic: Boolean = false) {\n        refresh(AlbumFilter.LIKED, automatic = automatic)\n    }\n""", rel)

    old_playlists = """    fun sync() {\n        viewModelScope.launch(Dispatchers.IO) {\n            _isRefreshing.value = true\n            syncUtils.syncSavedPlaylists()\n            syncUtils.syncAutoSyncPlaylists()\n            _isRefreshing.value = false\n        }\n    }\n"""
    new_playlists = """    fun sync(automatic: Boolean = false) {\n        if (_isRefreshing.value) return\n        viewModelScope.launch(Dispatchers.IO) {\n            _isRefreshing.value = true\n            try {\n                syncUtils.syncSavedPlaylists(automatic = automatic)\n                syncUtils.syncAutoSyncPlaylists(automatic = automatic)\n            } catch (e: Exception) {\n                reportException(e)\n            } finally {\n                _isRefreshing.value = false\n            }\n        }\n    }\n"""
    text = replace_once(text, old_playlists, new_playlists, rel)
    if not check_only:
        write(rel, text)


def patch_library_screens(check_only: bool) -> None:
    patches = {
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibrarySongsScreen.kt": [
            ("SongFilter.LIKED -> viewModel.syncLikedSongs()", "SongFilter.LIKED -> viewModel.syncLikedSongs(automatic = true)"),
            ("SongFilter.LIBRARY -> viewModel.syncLibrarySongs()", "SongFilter.LIBRARY -> viewModel.syncLibrarySongs(automatic = true)"),
        ],
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryAlbumsScreen.kt": [
            ("viewModel.sync()", "viewModel.sync(automatic = true)"),
        ],
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryArtistsScreen.kt": [
            ("viewModel.sync()", "viewModel.sync(automatic = true)"),
        ],
        "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryPlaylistsScreen.kt": [
            ("viewModel.sync()", "viewModel.sync(automatic = true)"),
        ],
    }
    for rel, replacements in patches.items():
        text = read(rel)
        assert_absent(text, "automatic = true", rel)
        for old, new in replacements:
            text = replace_once(text, old, new, rel)
        if not check_only:
            write(rel, text)


def patch_app_prewarm(check_only: bool) -> None:
    rel = "app/src/main/kotlin/com/nikhil/yt/App.kt"
    text = read(rel)
    assert_absent(text, "Startup WEB prewarm", rel)
    text = replace_once(
        text,
        "import com.nikhil.yt.innertube.models.YouTubeLocale\n",
        "import com.nikhil.yt.innertube.models.YouTubeLocale\nimport com.nikhil.yt.playback.audio.CapsuleInnerTubeXPlayer\n",
        rel,
    )
    text = replace_once(
        text,
        "import kotlinx.coroutines.withContext\n",
        "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.withTimeoutOrNull\n",
        rel,
    )
    anchor = """        applicationScope.launch(Dispatchers.IO) {\n            dataStore.data\n                .map { it[VisitorDataKey] }\n"""
    block = """        // Startup WEB prewarm is useful only for profiles that need the\n        // extractor/cipher stack. VisionOS stays completely cold and direct.\n        applicationScope.launch(Dispatchers.IO) {\n            try {\n                val prefs = dataStore.data.first()\n                val startupPolicy =\n                    prefs[AudioStreamPolicyKey]\n                        .toEnum(AudioStreamPolicy.VISIONOS)\n                        .normalizedForPlayback()\n                if (startupPolicy != AudioStreamPolicy.VISIONOS) {\n                    val hasVisitorData =\n                        withTimeoutOrNull(20_000L) {\n                            YouTube.authStates.first { state ->\n                                !state.visitorData.isNullOrBlank()\n                            }\n                        } != null\n                    if (hasVisitorData) {\n                        CapsuleInnerTubeXPlayer.prewarm()\n                    }\n                }\n            } catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (error: Exception) {\n                reportRecoverableException(\"App\", \"startup audio prewarm\", error)\n            }\n        }\n\n""" + anchor
    text = replace_once(text, anchor, block, rel)
    if not check_only:
        write(rel, text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    patch_safety(args.check)
    patch_downloads(args.check)
    patch_sync(args.check)
    patch_viewmodels(args.check)
    patch_library_screens(args.check)
    patch_app_prewarm(args.check)

    if args.check:
        print("final review step26 preconditions OK")
    else:
        print("final review step26 applied")


if __name__ == "__main__":
    main()
