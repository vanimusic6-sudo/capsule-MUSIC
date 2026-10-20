package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two exact startup/layout regressions until a device-backed Home
 * integration test is available. These are source-level invariants, NOT visual tests.
 */
class HomeStartupStabilityTest {
    private fun source(fileName: String): String {
        val relative = "src/main/kotlin/com/nikhil/yt/ui/screens/$fileName"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Home source not found: $fileName")
    }

    private fun viewModelSource(): String {
        val relative = "src/main/kotlin/com/nikhil/yt/viewmodels/HomeViewModel.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("HomeViewModel source not found")
    }

    @Test
    fun firstHomeRequestWaitsForTheInitialCookieSnapshotWithoutAnySwipe() {
        val vm = viewModelSource()
        val load = vm.substringAfter("private suspend fun load() {")
            .substringBefore("private suspend fun songLoad()")
        assertTrue(vm.contains("viewModelScope.launch(Dispatchers.IO) {\n            load()"))
        assertTrue(load.indexOf("val preferences = context.dataStore.data.first()") >= 0)
        assertTrue(load.indexOf("YouTube.cookie = startupCookie") < load.indexOf("YouTube.home().onSuccess"))
        assertTrue(load.contains("checkedContinuations < 2"))
        assertTrue(load.contains("cleaned.sections.isEmpty()"))
        assertTrue(load.contains("homeFeedError.value = true"))
        assertFalse(load.contains("delay(8_000L)"))
        assertFalse(load.contains("delay(2_000L)"))
        assertTrue(vm.contains("\"трендовое\""))
    }

    @Test
    fun incompleteKeepListeningPagesStayAtTheTopOfThePager() {
        val section = source("HomeScreenComponents.kt")
            .substringAfter("fun KeepListeningSection(")
            .substringBefore("if (pages.size > 1)")
        assertTrue(section.contains("distinctSongs.chunked(4)"))
        assertTrue(section.contains("verticalAlignment = Alignment.Top"))
        assertTrue(section.contains(".height(ListItemHeight * 4 + 8.dp)"))
    }

    @Test
    fun failedHomeRequestOffersExplicitRetryWithoutDiscardingOtherShelves() {
        val home = source("HomeScreen.kt")
        val collections = source("HomeCollectionsScreen.kt")
        assertTrue(collections.contains("feedError && home?.sections.isNullOrEmpty() && !loading"))
        assertTrue(collections.contains("onClick = viewModel::refresh"))
        assertTrue(home.contains("onRefresh = viewModel::refresh"))
    }
}
