package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capsule main chrome must be fixed, opaque, and owned exactly once by
 * Scaffold.topBar (Spotify model). Feeds scroll UNDER it, without reserving
 * phantom space or composing duplicate greeting rows.
 */
class HomeScrollableHeaderTest {
    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun activityOwnsOnePinnedHeaderForEveryMainRouteOnly() {
        val activity = source("MainActivity.kt")
        val topBar = activity.substringAfter("Scaffold(\n                                topBar = {")
            .substringBefore("bottomBar = {")
        assertTrue(topBar.contains("if (currentRoute in topLevelScreens && !active)"))
        assertTrue(topBar.contains("CapsulePinnedHeader()"))
        assertTrue(topBar.contains("TopSearch("))
        assertFalse(topBar.contains("CapsuleCanvasHeader()"))
        assertFalse(topBar.contains("rememberInfiniteTransition"))
    }

    @Test fun headerHasOpaqueBackgroundAndNoScrollOrAnimatedState() {
        val host = source("ui/screens/CapsuleRouteSurface.kt")
        val header = host.substringAfter("fun CapsulePinnedHeader()")
            .substringBefore("internal fun CapsuleRouteSurface(")
        assertTrue(header.contains(".background(MaterialTheme.colorScheme.surface)"))
        assertTrue(header.contains(".statusBarsPadding()"))
        assertTrue(header.contains(".height(AppBarHeight)"))
        assertTrue(header.contains("StandardHeaderTitle("))
        assertTrue(header.contains("R.drawable.settings"))
        assertFalse(header.contains("AnimatedVisibility("))
        assertFalse(header.contains("animateFloatAsState("))
        assertFalse(header.contains(".nestedScroll("))
        assertFalse(host.contains("RouteWithScrollAwayHeader"))
        assertFalse(host.contains("headerOffsetPx"))
        assertFalse(host.contains("CapsuleCanvasHeader"))
    }

    @Test fun feedsHaveNoDuplicatedGreetingItems() {
        val home = source("ui/screens/HomeScreen.kt")
        assertFalse(home.contains("capsule:feed-header"))
        assertFalse(home.contains("CapsuleCanvasHeader"))
        assertTrue(home.contains("contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()"))
        val others = listOf(
            "ui/screens/library/LibraryMixScreen.kt",
            "ui/screens/library/LibrarySongsScreen.kt",
            "ui/screens/library/LibraryAlbumsScreen.kt",
            "ui/screens/library/LibraryArtistsScreen.kt",
            "ui/screens/library/LibraryPlaylistsScreen.kt",
            "ui/screens/StatsScreen.kt",
            "ui/screens/HistoryScreen.kt",
        )
        others.forEach { path ->
            val feed = source(path)
            assertFalse("$path must not have duplicate greeting", feed.contains("capsule:feed-header"))
            assertFalse("$path must not render inline header", feed.contains("CapsuleCanvasHeader()"))
        }
    }

    @Test fun contentReservesExactlyOneRealBarAndStatusInset() {
        val activity = source("MainActivity.kt")
        val insets = activity.substringAfter("val playerAwareWindowInsets =")
            .substringBefore("appBarScrollBehavior(")
        assertTrue(insets.contains("currentRoute in topLevelScreens"))
        assertTrue(insets.contains("visibleStatusBarInsets.only(WindowInsetsSides.Top)"))
        assertTrue(insets.contains("windowsInsets.only(WindowInsetsSides.Top)"))
        assertTrue(insets.contains("top = if (currentRoute in topLevelScreens) AppBarHeight else 0.dp"))
        assertFalse(insets.contains("WindowInsets(top = AppBarHeight"))
        assertTrue(activity.contains("val visibleStatusBarInsets = WindowInsets.statusBars"))
    }

    @Test fun sameModernGreetingIsUsedOnAllPinnedTabs() {
        val chrome = source("ui/component/StandardChrome.kt")
        assertTrue(chrome.contains("val dayPeriod = rememberHomeDayPeriod()"))
        assertTrue(chrome.contains("val title = stringResource(dayPeriod.greetingId)"))
        assertTrue(chrome.contains("val guestIcon = homeGuestIcon("))
        assertFalse(chrome.contains("stringResource(R.string.app_name)"))
        assertFalse(chrome.contains("showHomeGreeting"))
    }

    @Test fun listenTogetherDoesNotShowPressedOverlays() {
        val together = source("ui/screens/settings/MusicTogetherScreen.kt")
        val switch = source("ui/component/CapsuleSoftSwitch.kt")
        val row = together.substringAfter("private fun ToggleRow(")
            .substringBefore("private fun StatusCard(")
        assertTrue(row.contains("CapsuleSoftSwitch("))
        assertTrue(row.contains("indication = null"))
        assertTrue(switch.contains("indication = null"))
        assertTrue(switch.contains("role = Role.Switch"))
    }
}
