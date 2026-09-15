package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.screens.MainTabNavigator
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.rememberMainTabNavigator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping a tab goes to that tab. Every time, from anywhere.
 *
 * It did not, and the reason is worth keeping written down because the code looked like the
 * canonical Android bottom-navigation pattern. That pattern assumes each tab is a *nested graph*, so
 * `popUpTo(start) { saveState }` saves one tab's own stack and `restoreState` puts that same tab's
 * stack back. This graph is flat: "everything above the start destination" is not one tab's stack,
 * it is whatever the user happens to have open.
 *
 * So tapping Home while in Settings saved the settings page under Home's key and restored it in the
 * same call — the tap did nothing at all. And opening an artist, switching to Library, then tapping
 * Home brought the artist back instead of Home.
 *
 * The fix pops non-tab destinations first and without saving them, so the saved stacks only ever
 * contain tabs. What it must not cost is the thing save/restore is actually there for, which is why
 * the last test here checks a tab still has its state after a round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class TabMeansTheTabTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController
    private lateinit var tabs: MainTabNavigator

    private val detailRoutes = listOf("settings", "settings/appearance", "local_playlist/5", "artist/a")

    private fun show() {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                tabs = rememberMainTabNavigator(controller)
                NavHost(controller, startDestination = Screens.Home.route) {
                    (Screens.MainScreens.map { it.route } + detailRoutes).forEach { route ->
                        composable(route) {
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Box(Modifier.fillMaxSize()) {
                                Button(onClick = { count++ }, modifier = Modifier.testTag("btn:$route")) {
                                    Text("$route=$count", modifier = Modifier.testTag("txt:$route"))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun open(route: String) {
        compose.runOnIdle { controller.navigate(route) }
        compose.waitForIdle()
    }

    private fun tap(route: String) {
        compose.runOnIdle { tabs.select(route) }
        compose.waitForIdle()
    }

    private fun here(): String? = compose.runOnIdle { controller.currentDestination?.route }

    private fun label(route: String): String =
        compose.onNodeWithTag("txt:$route", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("")

    @Test fun `Home from a settings page goes Home`() {
        show()
        open("settings")
        tap(Screens.Home.route)
        assertEquals(Screens.Home.route, here())
    }

    @Test fun `Home from deep inside settings goes Home`() {
        show()
        open("settings")
        open("settings/appearance")
        tap(Screens.Home.route)
        assertEquals(Screens.Home.route, here())
    }

    @Test fun `Home from a playlist opened inside another tab goes Home`() {
        show()
        tap(Screens.Library.route)
        open("local_playlist/5")
        tap(Screens.Home.route)
        assertEquals(Screens.Home.route, here())
    }

    /** The one that brought back the artist: it was saved under Home's key by an earlier tab tap. */
    @Test fun `Home does not bring back an artist opened before a tab switch`() {
        show()
        open("artist/a")
        tap(Screens.Library.route)
        assertEquals(Screens.Library.route, here())
        tap(Screens.Home.route)
        assertEquals(Screens.Home.route, here())
    }

    @Test fun `every tab is reachable from a detail screen`() {
        show()
        Screens.MainScreens.forEach { screen ->
            open("artist/a")
            tap(screen.route)
            assertEquals("tapping ${screen.route} from an artist", screen.route, here())
        }
    }

    @Test fun `switching tabs still keeps each tab's own state`() {
        show()
        tap(Screens.Library.route)
        repeat(3) { compose.onNodeWithTag("btn:${Screens.Library.route}").performClick() }
        compose.waitForIdle()
        assertEquals("${Screens.Library.route}=3", label(Screens.Library.route))

        tap(Screens.Home.route)
        assertEquals(Screens.Home.route, here())

        tap(Screens.Library.route)
        assertEquals(
            "the library came back blank, so save/restore was lost",
            "${Screens.Library.route}=3",
            label(Screens.Library.route),
        )
    }
}
