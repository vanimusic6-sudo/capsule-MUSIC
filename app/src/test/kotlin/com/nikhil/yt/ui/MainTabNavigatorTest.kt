package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.component.StandardNavigationBar
import com.nikhil.yt.ui.screens.MainTabNavigator
import com.nikhil.yt.ui.screens.ScreenTransitions
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.rememberMainTabNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainTabNavigatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var controller: NavHostController
    private lateinit var tabs: MainTabNavigator
    private val visited = mutableListOf<String>()
    private var reselected = 0

    private fun showNavigation(start: String = "home") {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                tabs = rememberMainTabNavigator(controller)
                val entry by controller.currentBackStackEntryAsState()
                DisposableEffect(controller) {
                    val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                        destination.route?.let(visited::add)
                    }
                    controller.addOnDestinationChangedListener(listener)
                    onDispose { controller.removeOnDestinationChangedListener(listener) }
                }
                Column {
                    NavHost(
                        controller, startDestination = start,
                        modifier = Modifier.fillMaxWidth().height(300.dp).clipToBounds(),
                        enterTransition = { ScreenTransitions.enter(initialState.destination.route, targetState.destination.route) },
                        exitTransition = { ScreenTransitions.exit(initialState.destination.route, targetState.destination.route) },
                        popEnterTransition = { ScreenTransitions.enter(initialState.destination.route, targetState.destination.route, true) },
                        popExitTransition = { ScreenTransitions.exit(initialState.destination.route, targetState.destination.route, true) },
                    ) {
                        composable("details") {
                            Box(Modifier.fillMaxSize().testTag("details")) { Text("Details") }
                        }
                        Screens.MainScreens.forEach { screen ->
                            composable(screen.route) {
                                var count by rememberSaveable { mutableIntStateOf(0) }
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("screen:${screen.route}")) {
                                    Button(onClick = { count++ }) { Text("${screen.route}:$count") }
                                }
                            }
                        }
                    }
                    StandardNavigationBar(Modifier.fillMaxWidth().height(80.dp), Screens.MainScreens, entry?.destination?.route.orEmpty()) {
                        tabs.select(it.route) { reselected++ }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun select(route: String) = compose.runOnIdle { tabs.select(route) { reselected++ } }

    private fun settle() {
        // The pending last request may start a second transition after the first resumes.
        repeat(3) {
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }

    private fun assertSettled(route: String) {
        compose.onNodeWithTag("screen:$route").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(route, controller.currentDestination?.route)
            assertEquals(Lifecycle.State.RESUMED, controller.currentBackStackEntry?.lifecycle?.currentState)
        }
    }

    @Test fun rapidReversalsOpenOnlyTheLastRequestedTab() {
        showNavigation()
        select("history")
        compose.mainClock.advanceTimeByFrame()
        select("library")
        select("stats")
        select("home")
        select("library")
        compose.runOnIdle { assertEquals("history", controller.currentDestination?.route) }
        settle()
        assertSettled("library")
        assertEquals(listOf("home", "history", "library"), visited)
        compose.runOnIdle { controller.popBackStack() }
        settle()
        assertSettled("home")
    }

    @Test fun reselectingTheCurrentTabCancelsAnOlderPendingTab() {
        showNavigation()
        select("history")
        compose.mainClock.advanceTimeByFrame()
        select("library")
        select("history")
        settle()
        assertSettled("history")
        assertEquals(listOf("home", "history"), visited)
        assertEquals(1, reselected)
    }

    @Test fun backDuringTransitionInvalidatesThePendingTab() {
        showNavigation()
        select("history")
        compose.mainClock.advanceTimeByFrame()
        select("library")
        compose.runOnIdle { controller.popBackStack() }
        settle()
        assertSettled("home")
        assertFalse(visited.contains("library"))
    }

    @Test fun backDuringADetailTransitionReturnsToASettledMainScreen() {
        showNavigation()
        compose.runOnIdle { controller.navigate("details") }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { controller.popBackStack() }
        settle()
        assertSettled("home")
    }

    @Test fun tabStateSurvivesRoundTripsWhenLibraryIsTheStartDestination() {
        showNavigation(start = "library")
        select("history")
        settle()
        compose.onNodeWithText("history:0").performClick()
        select("home")
        settle()
        select("history")
        settle()
        compose.onNodeWithText("history:1").assertIsDisplayed()
        compose.runOnIdle { controller.popBackStack() }
        settle()
        assertSettled("library")
    }
}
