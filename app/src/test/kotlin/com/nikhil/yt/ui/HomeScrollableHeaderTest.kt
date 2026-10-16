package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guard for header position; real scroll gestures require instrumentation. */
class HomeScrollableHeaderTest {
    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun theHeaderIsTheFirstLazyItemNotAStickyOverlay() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("item(key = \"home:header\""))
        assertTrue(home.indexOf("item(key = \"home:header\"") < home.indexOf("item(key = \"home:quick-hero\""))
        assertTrue(home.contains("top = (oldContentPadding.calculateTopPadding() - AppBarHeight)"))
        assertFalse(home.contains("stickyHeader("))
    }

    @Test fun homeSkipsScaffoldBarAndItsNestedScrollConnection() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("if (shouldShowTopBar && currentRoute != Screens.Home.route)"))
        assertTrue(activity.contains("if (currentRoute == Screens.Home.route) Modifier"))
        assertTrue(activity.contains("else Modifier.nestedScroll(searchBarScrollBehavior.nestedScrollConnection)"))
    }
}
