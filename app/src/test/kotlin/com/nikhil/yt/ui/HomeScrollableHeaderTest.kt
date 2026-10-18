package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards: greeting/account/settings are the first native item
 * of each main page's existing scrolling surface, never a separate animated
 * overlay that must guess when a fling has reached the top.
 */
class HomeScrollableHeaderTest {
    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun homeHeaderIsFirstFeedItemWithNoIndependentScrollOwner() {
        val home = source("ui/screens/HomeScreen.kt")
        val start = home.substringAfter("LazyColumn(\n                state = lazylistState")
            .substringBefore("quickPicks?.takeIf")
        assertTrue(start.contains("item(key = \"capsule:feed-header\")"))
        assertTrue(start.contains("CapsuleCanvasHeader()"))
        assertTrue(home.contains("contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()"))
        assertFalse(home.contains("stickyHeader("))
    }

    @Test fun everyLibraryLayoutPutsCanvasHeaderAheadOfFilter() {
        listOf("LibraryMixScreen", "LibraryPlaylistsScreen", "LibrarySongsScreen",
               "LibraryAlbumsScreen", "LibraryArtistsScreen").forEach { name ->
            val source = source("ui/screens/library/$name.kt")
            val header = source.indexOf("key = \"capsule:feed-header\"")
            val filter = source.indexOf("key = \"filter\"")
            assertTrue("$name: greeting before filter chips", header in 0 until filter)
            assertTrue("$name: common greeting", source.contains("CapsuleCanvasHeader()"))
            if (name != "LibrarySongsScreen") {
                assertTrue("$name: grid header spans the entire width",
                    source.contains("key = \"capsule:feed-header\",\n" +
                        "                        span = { GridItemSpan(maxLineSpan) }"))
            }
        }
    }

    @Test fun historyAndStatsAlsoScrollTheirHeaderWithTheFeed() {
        listOf("HistoryScreen", "StatsScreen").forEach { name ->
            val source = source("ui/screens/$name.kt")
            val list = source.substringAfter("LazyColumn(")
            val header = list.indexOf("item(key = \"capsule:feed-header\")")
            assertTrue("$name starts with a real header row", header >= 0)
            assertTrue("$name header appears before content rows", header < list.indexOf("item {"))
        }
    }

    @Test fun routeHostHasNoFloatingHeaderAnimationsOrScrollInterception() {
        val routes = source("ui/screens/CapsuleRouteSurface.kt")
        val activity = source("MainActivity.kt")
        assertTrue(routes.contains("fun CapsuleCanvasHeader()"))
        assertTrue(routes.contains("StandardHeaderTitle("))
        assertTrue(routes.contains("LocalCapsuleHeaderNavController.current"))
        assertFalse(routes.contains("RouteWithScrollAwayHeader"))
        assertFalse(routes.contains("scrollAwayConnection"))
        assertFalse(routes.contains("headerOffsetPx"))
        assertFalse(routes.contains("NestedScrollConnection"))
        assertFalse(routes.contains(".nestedScroll("))
        assertFalse(routes.contains("TopAppBar("))
        assertFalse(routes.contains("animateFloatAsState"))
        assertFalse(routes.contains("rememberCoroutineScope"))
        assertFalse(routes.contains("WindowInsets.systemBars"))
        assertTrue(activity.contains("LocalCapsuleHeaderNavController provides navController"))
        assertTrue(activity.contains("LocalCapsuleHeaderAccountName provides headerAccountName"))
    }

    @Test fun greetingRemainsModernOnEveryPageAndNeverReturnsTheOldLogo() {
        val chrome = source("ui/component/StandardChrome.kt")
        assertTrue(chrome.contains("val dayPeriod = rememberHomeDayPeriod()"))
        assertTrue(chrome.contains("val title = stringResource(dayPeriod.greetingId)"))
        assertTrue(chrome.contains("val guestIcon = homeGuestIcon("))
        assertFalse(chrome.contains("stringResource(R.string.app_name)"))
        assertFalse(chrome.contains("showHomeGreeting"))
    }

    @Test fun listenTogetherAndSwitchTouchSurfacesDoNotDrawPressedScrims() {
        val together = source("ui/screens/settings/MusicTogetherScreen.kt")
        val softSwitch = source("ui/component/CapsuleSoftSwitch.kt")
        val row = together.substringAfter("private fun ToggleRow(")
            .substringBefore("private fun StatusCard(")
        assertTrue(row.contains("CapsuleSoftSwitch("))
        assertTrue(row.contains("indication = null"))
        assertFalse(row.contains("SwitchDefaults"))
        assertFalse(row.contains("R.drawable.close"))
        assertTrue(softSwitch.contains("indication = null"))
        assertTrue(softSwitch.contains("interactionSource = pressSource"))
        assertTrue(softSwitch.contains("role = Role.Switch"))
    }
}
