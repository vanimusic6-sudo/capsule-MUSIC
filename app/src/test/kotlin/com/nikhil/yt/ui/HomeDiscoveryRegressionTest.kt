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
    fun firstScreenStaysStableAndAllDiscoveryLivesInOnePlace() {
        val home = source("ui/screens/HomeScreen.kt")
        val collections = source("ui/screens/HomeCollectionsScreen.kt")
        val components = source("ui/screens/HomeScreenComponents.kt")
        val navigation = source("ui/screens/NavigationBuilder.kt")
        assertTrue(home.contains("home:quick-hero"))
        assertTrue(home.contains("home:keep-listening"))
        assertTrue(home.contains("home:collections-button"))
        assertTrue(home.contains("navController.navigate(\"home_collections\")"))
        assertFalse(home.contains("home:taste"))
        assertFalse(home.contains("ScrollActionButton("))
        assertFalse(home.contains("R.drawable.shuffle"))
        assertTrue(collections.contains("collections:taste"))
        assertTrue(collections.contains("collections:forgotten"))
        assertTrue(collections.contains("collections:listeners"))
        assertTrue(collections.contains("collections:releases"))
        assertTrue(navigation.contains("routeComposable(\"home_collections\")"))
        assertTrue(navigation.contains("hiltViewModel(homeEntry)"))
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
    fun recentListeningStillHasItsOwnStableMainHomeSlot() {
        val vm = source("viewmodels/HomeViewModel.kt")
        val dao = source("db/DatabaseDao.kt")
        val screen = source("ui/screens/HomeScreen.kt")
        assertTrue(vm.contains("recentlyListenedSongs(limit = 16).first()"))
        assertTrue(dao.contains("fun recentlyListenedSongs(limit: Int = 16)"))
        assertFalse(vm.contains("locallyPlayed.drop(4)"))
        assertTrue(screen.contains("home:keep-listening"))
        assertTrue(screen.contains("rememberLazyListState()"))
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
    fun newReleasesAppearOnceInCollections() {
        val screen = source("ui/screens/HomeCollectionsScreen.kt")
        assertTrue(screen.contains("fun isCollectionsReleaseShelf(section: HomePage.Section)"))
        assertTrue(screen.contains("!hasRelease"))
        assertTrue(screen.contains("original.copy(items = releases)"))
        assertTrue(screen.contains("collections:releases"))
        assertTrue(screen.contains("if (isCollectionsReleaseShelf(it)) \"releases\""))
    }

    @Test
    fun verticalScrollingDoesNotMoveHorizontalShelves() {
        val home = source("ui/screens/HomeScreen.kt")
        val collections = source("ui/screens/HomeCollectionsScreen.kt")
        val components = source("ui/screens/HomeScreenComponents.kt")
        assertTrue(home.contains("derivedStateOf { !listState.isScrollInProgress }"))
        assertTrue(collections.contains("derivedStateOf { !listState.isScrollInProgress }"))
        assertTrue(collections.contains("userScrollEnabled = horizontalEnabled.value"))
        val sections = listOf(
            "QuickPicksSection", "KeepListeningSection", "ForgottenFavoritesSection",
            "HomePageSectionContent", "MoodAndGenresSection", "CommunityPlaylistsSection",
            "TasteRecommendationsSection",
        )
        for (name in sections) {
            val body = components.substringAfter("fun $name(").substringBefore("\n}\n")
            assertTrue("$name should support vertical gesture locking",
                body.contains("userScrollEnabled: Boolean = true"))
            assertTrue("$name must disable horizontal gestures during vertical scrolling",
                body.contains("userScrollEnabled = userScrollEnabled"))
        }
    }

    @Test
    fun musicCardsAndNativeScrollingStayEnergyEfficient() {
        val components = source("ui/screens/HomeScreenComponents.kt")
        val home = source("ui/screens/HomeScreen.kt")
        val collections = source("ui/screens/HomeCollectionsScreen.kt")
        assertTrue(components.contains("thumbnailRatio = 1f"))
        assertTrue(home.contains("overscrollEffect = null"))
        assertTrue(collections.contains("overscrollEffect = null"))
        assertFalse(home.contains("initialVelocity *"))
        assertFalse(home.contains("snapshotFlow {"))
        assertTrue(collections.contains("snapshotFlow {"))
        assertTrue(collections.contains("distinctUntilChanged()"))
    }
}
