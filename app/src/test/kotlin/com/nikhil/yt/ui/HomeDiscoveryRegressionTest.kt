package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast source-level guardrails for Home. Frame times and recommendation quality
 * still require actual on-device measurements with real listening histories.
 */
class HomeDiscoveryRegressionTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test
    fun firstScreenDoesNotRepeatQuickPicksOrStartInfiniteCardMarquees() {
        val home = source("ui/screens/HomeScreen.kt")
        val components = source("ui/screens/HomeScreenComponents.kt")
        assertTrue(home.contains("home:quick-hero"))
        assertFalse(home.contains("home:quick-list"))
        assertTrue(home.indexOf("home:taste") < home.indexOf("home:forgotten"))
        assertTrue(components.contains("animateTitle = false"))
        assertTrue(components.contains("itemWidth = 184.dp"))
    }

    @Test
    fun recommendationStreamRejectsSeenTracksAndNegativeFeedback() {
        val engine = source("utils/ForYouSuggestionEngine.kt")
        assertTrue(engine.contains("candidate.id !in heardIds"))
        assertTrue(engine.contains("candidate.id !in recentIds"))
        assertTrue(engine.contains("skip.skipCount >= 2"))
        assertTrue(engine.contains("artistCounts[artist]"))
        assertTrue(engine.contains("distinctBy { it.artists.firstOrNull()"))
    }

    @Test
    fun curatedHomeRemainsBoundedAndRetainsRealPersonalShelves() {
        val vm = source("viewmodels/HomeViewModel.kt")
        assertTrue(vm.contains("attemptedContinuations.add(continuation)"))
        assertTrue(vm.contains("continuationBudget = 4"))
        assertTrue(vm.contains("sectionInterest(it, familiarArtists)"))
        assertTrue(vm.contains("if (affinity >= 2)"))
        assertTrue(vm.contains("homeTasteArtists = familiarArtists"))
    }
}
