package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nikhil.yt.ui.component.selectedTabIndex
import com.nikhil.yt.ui.screens.Screens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The navigation bar is on screen everywhere now, including on screens that are not tabs.
 *
 * That turned a dormant bug into a visible one. Both call sites used to end in `.coerceAtLeast(0)`,
 * so "no tab matches this route" became index 0 — opening an artist from the library would have
 * slid the highlight across to Home, and opening one from Home would have looked right purely by
 * luck. Nobody could see it while the bar hid itself on those screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class NavigationBarSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = Screens.MainScreens

    /**
     * One setContent per rule, so every test drives the same composition by changing the route —
     * which is also what the app does.
     */
    private lateinit var seen: MutableList<Int>
    private var route by mutableStateOf(Screens.Home.route)

    private fun show(initialRoute: String) {
        route = initialRoute
        seen = mutableListOf()
        compose.setContent { seen += selectedTabIndex(route, items) }
        compose.waitForIdle()
    }

    private fun navigateTo(next: String): Int {
        route = next
        compose.waitForIdle()
        return seen.last()
    }

    @Test fun `each tab highlights itself`() {
        show(items.first().route)
        items.forEachIndexed { expected, screen ->
            assertEquals(
                "${screen.route} highlighted the wrong tab",
                expected,
                navigateTo(screen.route),
            )
        }
    }

    @Test fun `a screen opened from a tab keeps that tab highlighted`() {
        show(Screens.Library.route)
        val onLibrary = seen.last()

        // An artist, a playlist, a settings page: none of them is a tab.
        listOf("artist/abc", "online_playlist/xyz", "settings", "settings/appearance").forEach {
            assertEquals(
                "$it moved the highlight off the tab it was opened from",
                onLibrary,
                navigateTo(it),
            )
        }
    }

    @Test fun `the highlight follows the last tab actually visited`() {
        show(Screens.Home.route)

        val onHistory = navigateTo(Screens.History.route)
        assertEquals(items.indexOf(Screens.History), onHistory)

        assertEquals("settings fell back to the first tab", onHistory, navigateTo("settings"))
    }

    @Test fun `a sub-route of a tab still counts as that tab`() {
        show(Screens.Home.route)
        assertEquals(
            items.indexOf(Screens.Library),
            navigateTo("${Screens.Library.route}/songs"),
        )
    }
}
