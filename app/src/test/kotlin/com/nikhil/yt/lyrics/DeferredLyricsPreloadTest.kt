package com.nikhil.yt.lyrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredLyricsPreloadTest {
    @Test fun aMillionTrackQueueReadsOnlyTheRequestedUpcomingItems() {
        val reads = mutableListOf<Int>()
        val result = nextLyricsPreloadItems(500, 1_000_000, 2) { reads += it; "song-$it" }
        assertEquals(listOf(501, 502), reads)
        assertEquals(listOf("song-501", "song-502"), result)
        assertTrue(nextLyricsPreloadItems(999_999, 1_000_000, 10) { reads += it; it }.isEmpty())
        assertTrue(nextLyricsPreloadItems(-1, 1_000_000, 10) { error("No queue read on clear") }.isEmpty())
    }

    @Test fun disabledPreloadDoesNotSnapshotOrLoadAnyQueueItems() = runTest {
        var reads = 0
        val job = launch { preloadSettledLyrics<Int>({ 0 }, { reads++; listOf(1) }, { error("disabled") }) }
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(0, reads)
    }

    @Test fun rapidSkipsCancelBeforeEvenSnapshottingTheOldSelection() = runTest {
        var reads = 0
        val old = launch { preloadSettledLyrics({ 2 }, { reads++; listOf("old") }, { error("obsolete") }) }
        advanceTimeBy(400); old.cancel(); runCurrent()
        var loaded = emptyList<String>()
        launch { preloadSettledLyrics({ 2 }, { reads++; listOf("new", "next") }, { loaded = it }) }
        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(listOf("new", "next"), loaded)
    }

    @Test fun stoppingTheOwnerCancelsAnAdmittedProviderRequest() = runTest {
        var started = false
        var cancelled = false
        val owner = launch {
            preloadSettledLyrics({ 1 }, { listOf("next") }, {
                started = true
                try { awaitCancellation() } finally { cancelled = true }
            })
        }
        advanceTimeBy(801); runCurrent(); assertTrue(started)
        owner.cancel(); runCurrent(); assertTrue(cancelled)
    }
}
