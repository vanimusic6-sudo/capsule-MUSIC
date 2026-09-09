from pathlib import Path
import sys

CHECK_ONLY = "--check" in sys.argv


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def patch(path: str, transforms):
    file = Path(path)
    text = file.read_text()
    original = text
    for label, old, new in transforms:
        text = replace_once(text, old, new, f"{path}: {label}")
    if CHECK_ONLY:
        return
    if text == original:
        raise SystemExit(f"{path}: no changes produced")
    file.write_text(text)


patch(
    "app/src/main/kotlin/com/nikhil/yt/playback/DownloadUtil.kt",
    [
        (
            "remove early download timestamp",
            "    @Volatile private var lastDownloadResolveStartMs = 0L\n",
            "",
        ),
        (
            "remove pre-scheduler spacing term",
            "                        (cooldownUntilMs - nowMs).coerceAtLeast(0L),\n                        (lastDownloadResolveStartMs + DOWNLOAD_RESOLVE_SPACING_MS - nowMs)\n                            .coerceAtLeast(0L),\n",
            "                        (cooldownUntilMs - nowMs).coerceAtLeast(0L),\n",
        ),
        (
            "stop claiming start before scheduler",
            "                if (waitMs <= 0L) {\n                    lastDownloadResolveStartMs = System.currentTimeMillis()\n                    return@withLock\n                }\n",
            "                if (waitMs <= 0L) {\n                    return@withLock\n                }\n",
        ),
        (
            "remove obsolete spacing constant",
            "        private const val DOWNLOAD_RESOLVE_SPACING_MS = 4_000L\n",
            "",
        ),
    ],
)

patch(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioResolveScheduler.kt",
    [
        (
            "import delay",
            "import kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\n",
            "import kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.ensureActive\n",
        ),
        (
            "scheduler constructor and actual-start pacer state",
            "internal class AudioResolveScheduler {\n    private class Preempted : CancellationException(\"Foreground playback needs the resolver\")\n",
            "internal class AudioResolveScheduler(\n    private val monotonicNowMs: () -> Long = { System.nanoTime() / 1_000_000L },\n    private val downloadStartSpacingMs: Long = DOWNLOAD_START_SPACING_MS,\n) {\n    private class Preempted : CancellationException(\"Foreground playback needs the resolver\")\n",
        ),
        (
            "last actual download start",
            "    private val waiting = mutableListOf<Ticket>()\n    private var active: Ticket? = null\n",
            "    private val waiting = mutableListOf<Ticket>()\n    private var active: Ticket? = null\n    private var lastDownloadStartMs: Long? = null\n",
        ),
        (
            "pace worker immediately before transport block",
            "                    val work = async(start = CoroutineStart.LAZY) { block() }\n",
            "                    val work = async(start = CoroutineStart.LAZY) {\n                        awaitDownloadStartWindow(ticket.priority)\n                        block()\n                    }\n",
        ),
        (
            "insert actual-start gate",
            "    private fun dispatch() {\n",
            "    private suspend fun awaitDownloadStartWindow(priority: AudioResolvePriority) {\n        if (priority != AudioResolvePriority.DOWNLOAD || downloadStartSpacingMs <= 0L) return\n\n        val waitMs =\n            synchronized(lock) {\n                lastDownloadStartMs\n                    ?.let { lastStart ->\n                        (lastStart + downloadStartSpacingMs - monotonicNowMs()).coerceAtLeast(0L)\n                    }\n                    ?: 0L\n            }\n\n        if (waitMs > 0L) delay(waitMs)\n        currentCoroutineContext().ensureActive()\n        synchronized(lock) {\n            lastDownloadStartMs = monotonicNowMs()\n        }\n    }\n\n    private fun dispatch() {\n",
        ),
        (
            "add pacing constant",
            "    private fun preemptBackground() {\n        val running = active ?: return\n        if (waiting.any { it.priority.outranks(running.priority) }) {\n            running.preempted = true\n            running.worker?.cancel(Preempted())\n        }\n    }\n}\n",
            "    private fun preemptBackground() {\n        val running = active ?: return\n        if (waiting.any { it.priority.outranks(running.priority) }) {\n            running.preempted = true\n            running.worker?.cancel(Preempted())\n        }\n    }\n\n    private companion object {\n        const val DOWNLOAD_START_SPACING_MS = 4_000L\n    }\n}\n",
        ),
    ],
)

patch(
    "app/src/test/kotlin/com/nikhil/yt/playback/audio/AudioResolveSchedulerTest.kt",
    [
        (
            "add actual start pacing regression",
            "    @Test fun cancelledQueuedTrackNeverContactsTheTransport() = runTest {\n",
            "    @Test fun downloadRestartAfterPreemptionKeepsActualStartSpacing() = runTest {\n        val scheduler =\n            AudioResolveScheduler(\n                monotonicNowMs = { testScheduler.currentTime },\n                downloadStartSpacingMs = 4_000L,\n            )\n        val starts = mutableListOf<Long>()\n        val download = async {\n            scheduler.run(\"download\", AudioResolvePriority.DOWNLOAD) {\n                starts += testScheduler.currentTime\n                delay(10_000)\n                42\n            }\n        }\n        runCurrent()\n        assertEquals(listOf(0L), starts)\n\n        advanceTimeBy(500)\n        val playback = async {\n            scheduler.run(\"current\", AudioResolvePriority.PLAYBACK) { 7 }\n        }\n        runCurrent()\n        assertEquals(7, playback.await())\n\n        advanceTimeBy(3_499)\n        runCurrent()\n        assertEquals(listOf(0L), starts)\n\n        advanceTimeBy(1)\n        runCurrent()\n        assertEquals(listOf(0L, 4_000L), starts)\n        download.cancelAndJoin()\n    }\n\n    @Test fun cancelledQueuedTrackNeverContactsTheTransport() = runTest {\n",
        ),
    ],
)

patch(
    "app/src/main/kotlin/com/nikhil/yt/utils/SyncUtils.kt",
    [
        (
            "full sync automatic flag",
            "    suspend fun performFullSync() = withContext(Dispatchers.IO) {\n",
            "    suspend fun performFullSync(automatic: Boolean = false) = withContext(Dispatchers.IO) {\n",
        ),
        (
            "propagate automatic flag through full sync",
            "                    syncLikedSongs()\n                    syncLibrarySongs()\n\n                    listOf(\n                        async { syncLikedAlbums() },\n                        async { syncArtistsSubscriptions() },\n                    ).awaitAll()\n                    \n                    syncSavedPlaylists()\n                    syncAutoSyncPlaylists()\n",
            "                    syncLikedSongs(automatic = automatic)\n                    syncLibrarySongs(automatic = automatic)\n\n                    listOf(\n                        async { syncLikedAlbums(automatic = automatic) },\n                        async { syncArtistsSubscriptions(automatic = automatic) },\n                    ).awaitAll()\n                    \n                    syncSavedPlaylists(automatic = automatic)\n                    syncAutoSyncPlaylists(automatic = automatic)\n",
        ),
    ],
)

patch(
    "app/src/main/kotlin/com/nikhil/yt/viewmodels/LibraryViewModels.kt",
    [
        (
            "make full library sync automatic-aware",
            "    val syncAllLibrary = {\n         viewModelScope.launch(Dispatchers.IO) {\n             try {\n                 syncUtils.performFullSync()\n             } catch (e: Exception) {\n                 timber.log.Timber.e(e, \"Error during manual sync\")\n             }\n         }\n    }\n",
            "    fun syncAllLibrary(automatic: Boolean = false) {\n        viewModelScope.launch(Dispatchers.IO) {\n            try {\n                syncUtils.performFullSync(automatic = automatic)\n            } catch (e: Exception) {\n                timber.log.Timber.e(e, \"Error during library sync\")\n            }\n        }\n    }\n",
        ),
    ],
)

patch(
    "app/src/main/kotlin/com/nikhil/yt/ui/screens/library/LibraryMixScreen.kt",
    [
        (
            "mark screen-entry full sync automatic",
            "                 viewModel.syncAllLibrary()\n",
            "                 viewModel.syncAllLibrary(automatic = true)\n",
        ),
    ],
)

if CHECK_ONLY:
    print("Final field-test cleanup preconditions OK")
else:
    print("Final field-test cleanup applied")
