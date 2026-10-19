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
    fun feedDoesNotThrowAwayYouTubePersonalizationOrMixedPlaylists() {
        val vm = source("viewmodels/HomeViewModel.kt")
        assertTrue(vm.contains("attemptedContinuations.add(continuation)"))
        assertTrue(vm.contains("continuationBudget = 10"))
        assertTrue(vm.contains("sectionInterest(it, familiarArtists)"))
        assertTrue(vm.contains("section.copy(items = items)"))
        assertFalse(vm.contains("if (affinity >= 2)"))
        assertTrue(vm.contains("item is PlaylistItem"))
        assertTrue(vm.contains("YouTube.newReleaseAlbums()"))
        assertTrue(vm.contains("homeTasteArtists = familiarArtists"))
    }

    @Test
    fun recentListeningIsNotTheFifthMostPlayedTrack() {
        val vm = source("viewmodels/HomeViewModel.kt")
        val dao = source("db/DatabaseDao.kt")
        val screen = source("ui/screens/HomeScreen.kt")
        assertTrue(vm.contains("recentlyListenedSongs(limit = 16).first()"))
        assertTrue(dao.contains("fun recentlyListenedSongs(limit: Int = 16)"))
        assertFalse(vm.contains("locallyPlayed.drop(4)"))
        assertTrue(screen.contains("home:keep-listening"))
    }

    @Test
    fun ytMusicDoesNotRequireOptionalPlaylistMenuButtonsToShowMixes() {
        val parser = listOf(
            File("../innertube/src/main/kotlin/com/nikhil/yt/innertube/pages/HomePage.kt"),
            File("innertube/src/main/kotlin/com/nikhil/yt/innertube/pages/HomePage.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing innertube HomePage parser")
        val provider = listOf(
            File("../innertube/src/main/kotlin/com/nikhil/yt/innertube/YouTube.kt"),
            File("innertube/src/main/kotlin/com/nikhil/yt/innertube/YouTube.kt"),
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing innertube YouTube client")
        assertTrue(parser.contains("radioEndpoint = renderer.menu?.menuRenderer?.items?.find"))
        assertFalse(parser.contains("watchPlaylistEndpoint ?: return null"))
        assertTrue(provider.contains("continuation = continuation, setLogin = true"))
        assertTrue(provider.contains("browseId = \"FEmusic_new_releases_albums\", setLogin = true"))
    }

    @Test
    fun musicCardsAndEdgesUseMusicLayoutNotVideoLayout() {
        val components = source("ui/screens/HomeScreenComponents.kt")
        val screen = source("ui/screens/HomeScreen.kt")
        assertTrue(components.contains("thumbnailRatio = 1f"))
        assertTrue(screen.contains("overscrollEffect = null"))
        assertTrue(screen.contains("flingBehavior = homeFling"))
        assertFalse(screen.contains("height(430.dp)"))
        assertTrue(screen.contains("home:youtube-new-release-albums"))
    }
}
