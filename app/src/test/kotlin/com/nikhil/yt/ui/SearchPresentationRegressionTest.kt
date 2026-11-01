package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the user-visible regressions that previously hid entire search
 * categories or made rows reflow after showing only "Top result".
 * These are source contracts; latency and real frame timings still require
 * an Android device and field capture.
 */
class SearchPresentationRegressionTest {
    private fun source(path: String): String = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path"),
        File("innertube/src/main/kotlin/$path"),
        File("../innertube/src/main/kotlin/$path"),
    ).firstOrNull(File::isFile)?.readText() ?: error("No source file $path")

    @Test
    fun summaryIsPublishedOnlyOnceAfterTheFullResponse() {
        val vm = source("com/nikhil/yt/viewmodels/OnlineSearchViewModel.kt")
        val ui = source("com/nikhil/yt/ui/screens/search/OnlineSearchResult.kt")
        assertEquals(
            "No intermediate Top-only Compose state is allowed",
            1,
            Regex("""\bsummaryPage\s*=""").findAll(vm).count(),
        )
        assertTrue(vm.contains("summaryPage = completed"))
        assertFalse(vm.contains("summaryPage = published"))
        assertTrue(vm.contains("fun retrySummary()"))
        assertTrue(vm.contains("private var summaryJob: Job? = null"))
        assertTrue(vm.contains("retrySummary()\n        viewModelScope.launch"))
        assertTrue(ui.contains("viewModel.summaryRequestFailed"))
        assertTrue(ui.contains("viewModel::retrySummary"))
    }

    @Test
    fun fullSearchRetainsParallelSongsAndAlbums() {
        val source = source("com/nikhil/yt/innertube/YouTube.kt")
        assertTrue(source.contains("val (songsFallback, albumsFallback) = coroutineScope"))
        assertTrue(source.contains("songs.await() to albums.await()"))
        assertTrue(source.contains("items = songsFallback.take(12)"))
        assertTrue(source.contains("items = albumsFallback.take(12)"))
    }

    @Test
    fun oneEasedImageRevealHasNoOpaqueUpgradeFlash() {
        val arrival = source("com/nikhil/yt/ui/component/ArtworkArrival.kt")
        val hero = source("com/nikhil/yt/ui/component/AlbumArtwork.kt")
        val wrapper = source("com/nikhil/yt/ui/component/CapsuleFadeAsyncImage.kt")
        assertTrue(arrival.contains("tween(320, easing = FastOutSlowInEasing)"))
        assertTrue(hero.contains("!request.loaded -> 0f"))
        assertTrue(hero.contains("upgradeFromPreview -> upgrade.value"))
        assertTrue(wrapper.contains(".crossfade(false)"))
        assertTrue(wrapper.contains("rememberArtworkArrivalAlpha(identity, decoded)"))
    }
}
