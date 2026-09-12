package com.nikhil.yt.ui

import com.nikhil.yt.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensTest {
    @Test fun openingHomeBeforeBuildingTheTabListKeepsEveryDestination() {
        assertEquals("home", Screens.Home.route)
        assertEquals(listOf("home", "stats", "history", "library"), Screens.MainScreens.map { it.route })
    }
}
