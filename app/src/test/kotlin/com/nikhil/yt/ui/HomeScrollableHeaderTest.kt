package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capsule main chrome is fixed inside each top-level destination, not an
 * independently animated Activity bar. NavHost must move page and header
 * together when Settings opens/closes, while their feed does not scroll it
 * away. Colors must match the underlying screen, including Home's wash.
 */
class HomeScrollableHeaderTest {
    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun navDestinationOwnsPinnedHeaderAndSharedSettingsTransition() {
        val activity = source("MainActivity.kt")
        val host = source("ui/screens/CapsuleRouteSurface.kt")
        val transition = source("ui/screens/ScreenTransitions.kt")
        val topBar = activity.substringAfter("Scaffold(\\n                                topBar = {")
            .substringBefore("bottomBar = {")
        assertTrue("search overlay remains in Scaffold", topBar.contains("TopSearch("))
        assertFalse("Activity bar would pop independently on Settings Back",
            activity.contains("CapsulePinnedHeader()"))
        assertTrue("main destination draws header inside NavHost scene",
            host.contains("if (mainDestination && LocalCapsulePinnedHeaderVisible.current)"))
        assertTrue(host.contains("CapsulePinnedHeader(route = route)"))
        assertTrue(host.contains("CapsuleRouteSurface {"))
        assertTrue(host.contains("Box(modifier = Modifier.fillMaxSize())"))
        assertTrue("Settings transition must not be replaced with a second independent animation",
            transition.contains("SettingsRouteMotion.ROOT"))
        assertTrue(transition.contains("if (isPop) width / 2 else -width / 2"))
        assertTrue("search must suppress the pinned background and buttons",
            activity.contains("LocalCapsulePinnedHeaderVisible provides !active"))
    }

    @Test fun headerBackgroundMatchesUnderlyingRouteAndHasNoScrollAnimations() {
        val host = source("ui/screens/CapsuleRouteSurface.kt")
        val home = source("ui/screens/HomeScreen.kt")
        val library = source("ui/screens/library/LibraryScreen.kt")
        val header = host.substringAfter("fun CapsulePinnedHeader(route: String)")
            .substringBefore("internal fun CapsuleRouteSurface(")
        assertTrue(header.contains("if (isHome) StandardChrome.background else MaterialTheme.colorScheme.surface"))
        assertTrue(header.contains(".background(base)"))
        assertTrue(header.contains(".statusBarsPadding()"))
        assertTrue(header.contains(".height(AppBarHeight)"))
        assertTrue(header.contains("StandardHeaderTitle("))
        assertTrue(header.contains("R.drawable.settings"))
        // Home and the pinned header use one identical opaque backdrop.
        // No short gradient ending at 430dp may leave horizontal seams.
        assertTrue(home.contains(".background(StandardChrome.background)"))
        assertFalse(home.contains(".height(430.dp)"))
        assertTrue(header.contains(".background(base)"))
        assertFalse(header.contains("val wash = Brush.verticalGradient"))
        assertFalse(header.contains(".then(backdrop)"))
        assertTrue(library.contains("val surfaceColor = MaterialTheme.colorScheme.surface"))
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
        // Under NavHost transition, the global route changes before the
        // departing feed leaves the screen. Its own top inset MUST stay fixed.
        val host = source("ui/screens/CapsuleRouteSurface.kt")
        assertTrue(host.contains("parentInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)"))
        assertTrue(host.contains(".add(WindowInsets.statusBars.only(WindowInsetsSides.Top))"))
        assertTrue(host.contains(".add(WindowInsets(top = AppBarHeight))"))
        assertTrue(host.contains("LocalPlayerAwareWindowInsets provides destinationInsets"))
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
