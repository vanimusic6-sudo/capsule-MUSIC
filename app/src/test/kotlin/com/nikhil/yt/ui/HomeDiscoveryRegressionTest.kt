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
    fun homeKeepsEveryRecommendationShelfAndCollectionsOnlyContainsMoods() {
        val home = source("ui/screens/HomeScreen.kt")
        val components = source("ui/screens/HomeScreenComponents.kt")
        val navigation = source("ui/screens/NavigationBuilder.kt")
        assertTrue(home.contains("home:quick-hero"))
        assertTrue(home.contains("home:keep-listening"))
        assertTrue(home.contains("home:taste"))
        assertTrue(home.contains("home:forgotten"))
        assertTrue(home.contains("home:listener-community-playlists"))
        assertTrue(home.contains("home:yt:"))
        assertTrue(home.contains("home:collections-button"))
        assertTrue(home.contains("navController.navigate(\"mood_and_genres\")"))
        assertFalse(home.contains("navController.navigate(\"home_collections\")"))
        assertFalse(navigation.contains("routeComposable(\"home_collections\")"))
        assertFalse(home.contains("ScrollActionButton("))
        assertFalse(home.contains("Spacer(Modifier.height(218.dp))"))
        assertTrue(home.contains("quickPicks?.takeIf { displayYouTube && it.isNotEmpty() }"))
        assertTrue(components.contains("animateTitle = false"))
        assertTrue(components.contains("itemWidth = 184.dp"))
    }

    @Test
    fun sourcePickerOnlyAffectsHomeAndOldForgottenShelfIsGone() {
        val home = source("ui/screens/HomeScreen.kt")
        val header = source("ui/screens/CapsuleRouteSurface.kt")
        val shelf = source("ui/screens/ExperimentalDiscoveryShelf.kt")
        assertTrue(home.contains("HomeFeedSource.parse(homeSourceSetting)"))
        assertTrue(home.contains("rareSources.isNotEmpty()"))
        assertTrue(home.contains("catalogHome.lanes.forEach"))
        assertTrue(home.contains("CatalogHomeRow("))
        assertFalse(home.contains("ExperimentalDiscoveryShelf("))
        assertTrue(home.contains("includeYouTube"))
        assertFalse(home.contains("ExperimentalForgottenShelf("))
        assertTrue(header.contains("if (isHome)"))
        assertTrue(header.contains("HomeFeedSource.entries.forEach"))
        assertTrue(header.contains("val active = source in effective"))
        assertTrue(header.contains("val next = effective.toMutableSet()"))
        assertTrue(header.contains("capsule_home_reset_youtube"))
        assertTrue(header.contains("setRaw(HomeFeedSource.DEFAULT)"))
        assertTrue(home.contains("home:catalog-loading"))
        assertTrue(home.contains("HomeLoadingShimmer()"))
        assertFalse(home.contains("CatalogHomeError("))
        assertTrue(shelf.contains("discoveryFromHome(source, tasteArtists)"))
        assertFalse(shelf.contains("ExperimentalForgottenShelf"))
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
    fun newReleasesAppearOnceOnHome() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("isYouTubeReleaseShelf(section: HomePage.Section)"))
        assertTrue(home.contains("!providerHasReleaseShelf"))
        assertTrue(home.contains("providerSection.copy(items = newReleaseAlbums)"))
        assertTrue(home.contains("home:youtube-new-release-albums"))
        assertTrue(home.contains("home:yt:releases"))
    }

    @Test
    fun verticalScrollingDoesNotMoveHorizontalShelves() {
        val home = source("ui/screens/HomeScreen.kt")
        val components = source("ui/screens/HomeScreenComponents.kt")
        assertTrue(home.contains("derivedStateOf { !lazylistState.isScrollInProgress }"))
        assertTrue(home.contains("userScrollEnabled = horizontalShelvesEnabled.value"))
        val sections = listOf(
            "QuickPicksSection", "KeepListeningSection", "ForgottenFavoritesSection",
            "HomePageSectionContent", "CommunityPlaylistsSection",
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
    fun capsuleCollectionsCardRestsAboveMiniPlayerWithoutMovingOtherShelves() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("item(key = \"home:collections-button\")"))
        assertTrue(home.contains("onClick = { navController.navigate(\"mood_and_genres\") }"))
        assertTrue(home.contains("shape = RoundedCornerShape(20.dp)"))
        assertTrue(home.contains("color = StandardChrome.panel"))
        assertTrue(home.contains("bottom = 40.dp"))
        assertTrue(home.contains("home_collections_subtitle"))
        assertFalse(home.contains("FilledTonalButton("))
        assertTrue(home.contains("homePage?.sections"))
        assertTrue(home.contains("home:keep-listening"))
    }

    @Test
    fun musicCardsAndNativeScrollingStayEnergyEfficient() {
        val components = source("ui/screens/HomeScreenComponents.kt")
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(components.contains("thumbnailRatio = 1f"))
        assertTrue(home.contains("overscrollEffect = null"))
        assertFalse(home.contains("initialVelocity *"))
        assertTrue(home.contains("snapshotFlow {"))
        assertTrue(home.contains("distinctUntilChanged()"))
        assertFalse(home.contains("ScrollActionButton("))
    }
}
