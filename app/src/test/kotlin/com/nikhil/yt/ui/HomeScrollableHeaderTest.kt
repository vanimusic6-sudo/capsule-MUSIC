package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared greeting/settings header is neither a LazyColumn row nor a
 * permanently pinned outer Scaffold app bar. It belongs to each NavHost
 * destination, alongside the list, and leaves when content scrolls down.
 */
class HomeScrollableHeaderTest {
    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun homeContentNoLongerHoldsHeaderInsideItsLazyList() {
        val home = source("ui/screens/HomeScreen.kt")
        assertFalse(home.contains("item(key = \"home:header\""))
        assertTrue(home.contains("item(key = \"home:quick-hero\""))
        assertTrue(home.contains("contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()"))
        assertFalse(home.contains("stickyHeader("))
        assertFalse(home.contains("StandardHeaderTitle("))
    }

    @Test fun mainScreensUseTheirOwnSeparateHeaderAndActivityOnlySearch() {
        val activity = source("MainActivity.kt")
        val routes = source("ui/screens/CapsuleRouteSurface.kt")
        assertFalse(activity.contains("if (shouldShowTopBar"))
        assertFalse(activity.contains("var shouldShowTopBar"))
        assertTrue(activity.contains("LocalCapsuleHeaderNavController provides navController"))
        assertTrue(activity.contains("LocalCapsuleHeaderAccountName provides headerAccountName"))
        assertTrue(activity.contains("if (currentRoute in topLevelScreens) Modifier"))
        assertTrue(routes.contains("showScrollAwayHeader"))
        assertTrue(routes.contains("route in setOf(\"home\", \"library\", \"history\", \"stats\")"))
        assertTrue(routes.contains("private fun CapsuleStandaloneHeader("))
        assertTrue(routes.contains("RouteWithScrollAwayHeader(entry)"))
        assertTrue(routes.contains(".nestedScroll(scrollAwayConnection)"))
        assertTrue(routes.contains("headerOffsetPx = (headerOffsetPx + consumed.y)"))
        assertTrue(routes.contains("available.y > 0f"))
        assertTrue(routes.contains("headerOffsetPx.toInt()"))
        assertTrue(routes.contains("revealJob[0] = scope.launch"))
        assertTrue(routes.contains("available.y > 0f && headerOffsetPx < 0f"))
        assertFalse(routes.contains("stickyHeader("))
    }

    @Test fun topHeaderDoesNotBounceBackFromFlingOrTinyOverscroll() {
        val routes = source("ui/screens/CapsuleRouteSurface.kt")
        assertTrue(routes.contains("val pullThresholdPx"))
        assertTrue(routes.contains("12.dp.toPx()"))
        assertTrue(routes.contains("val hideNoiseFloorPx"))
        assertTrue(routes.contains("source != NestedScrollSource.UserInput"))
        assertTrue(routes.contains("available.y >= pullThresholdPx * 3f"))
        assertTrue(routes.contains("positiveOverscroll >= pullThresholdPx"))
        assertTrue(routes.contains("revealJob[0]?.isActive != true"))
        assertTrue(routes.contains("headerOffsetPx = (headerOffsetPx + consumed.y)"))
        // An opaque bar avoids card/text 'afterimages' shining through its
        // fading/moving surface, exactly as seen in recording 9496.
        assertTrue(routes.contains("containerColor = MaterialTheme.colorScheme.surface"))
        assertTrue(routes.contains("scrolledContainerColor = MaterialTheme.colorScheme.surface"))
        assertFalse(routes.contains("containerColor = androidx.compose.ui.graphics.Color.Transparent"))
    }

    @Test fun greetingIsSharedAndNeverShowsOldCapsuleTextOrAvatar() {
        val activity = source("MainActivity.kt")
        val chrome = source("ui/component/StandardChrome.kt")
        val routes = source("ui/screens/CapsuleRouteSurface.kt")
        assertFalse(activity.contains("LaunchedEffect(navBackStackEntry) {\n                        shouldShowTopBar"))
        assertTrue(routes.contains("StandardHeaderTitle("))
        assertTrue(chrome.contains("val dayPeriod = rememberHomeDayPeriod()"))
        assertTrue(chrome.contains("val title = stringResource(dayPeriod.greetingId)"))
        assertTrue(chrome.contains("val guestIcon = homeGuestIcon("))
        assertFalse(chrome.contains("stringResource(R.string.app_name)"))
        assertFalse(chrome.contains("showHomeGreeting"))
    }
}
