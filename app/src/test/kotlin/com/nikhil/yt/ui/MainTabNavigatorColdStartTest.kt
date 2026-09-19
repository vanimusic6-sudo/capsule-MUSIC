package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.screens.MainTabNavigator
import com.nikhil.yt.ui.screens.ScreenTransitions
import com.nikhil.yt.ui.screens.rememberMainTabNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cold-start regression for the crash
 * `IllegalStateException: You cannot access the Navigator's state until the Navigator is attached`.
 *
 * The production layout remembers [MainTabNavigator] in the outer composition while `NavHost` lives
 * inside the `Scaffold` content, which Material 3 subcomposes during the measure pass. Every
 * composition-scoped effect declared next to [rememberMainTabNavigator] therefore runs strictly
 * before `NavHost` assigns `navController.graph`, i.e. while `ComposeNavigator` is still unattached.
 *
 * This test reproduces that exact ordering instead of asserting on an `isAttached` guard, so it
 * fails for any implementation that reaches into navigator state from a composition effect, and it
 * then verifies navigation, destination lifecycle and Back still behave once attachment happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class MainTabNavigatorColdStartTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController
    private lateinit var tabs: MainTabNavigator
    private var attachedWhenOuterEffectsRan: Boolean? = null
    private var graphSetWhenOuterEffectsRan: Boolean? = null

    private fun composeNavigator(): ComposeNavigator =
        controller.navigatorProvider.getNavigator(ComposeNavigator::class.java)

    private fun startColdLaunch() {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                tabs = rememberMainTabNavigator(controller)

                // Runs before the Scaffold subcomposition measures NavHost into existence.
                DisposableEffect(Unit) {
                    val navigator =
                        controller.navigatorProvider.getNavigator(ComposeNavigator::class.java)
                    attachedWhenOuterEffectsRan = navigator.isAttached
                    graphSetWhenOuterEffectsRan = runCatching { controller.graph }.isSuccess
                    // A tab tap racing the very first frame must not crash either.
                    tabs.select("history")
                    onDispose { }
                }

                Scaffold { padding ->
                    NavHost(
                        navController = controller,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize().padding(padding),
                        enterTransition = {
                            ScreenTransitions.enter(
                                initialState.destination.route,
                                targetState.destination.route,
                            )
                        },
                        exitTransition = {
                            ScreenTransitions.exit(
                                initialState.destination.route,
                                targetState.destination.route,
                            )
                        },
                        popEnterTransition = {
                            ScreenTransitions.enter(
                                initialState.destination.route,
                                targetState.destination.route,
                                isPop = true,
                            )
                        },
                        popExitTransition = {
                            ScreenTransitions.exit(
                                initialState.destination.route,
                                targetState.destination.route,
                                isPop = true,
                            )
                        },
                    ) {
                        listOf("home", "history", "library").forEach { route ->
                            composable(route) {
                                Box(Modifier.fillMaxSize().testTag("screen:$route")) { Text(route) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun coldStartSurvivesEffectsRunningBeforeNavHostAttachesTheNavigator() {
        startColdLaunch()

        // The dangerous window is real: outer effects ran before NavHost set the graph.
        assertEquals(false, attachedWhenOuterEffectsRan)
        assertEquals(false, graphSetWhenOuterEffectsRan)

        // ...and once the subcomposition attached it, navigation is fully functional.
        assertTrue(composeNavigator().isAttached)
        assertEquals("home", controller.currentDestination?.route)
        assertEquals(
            Lifecycle.State.RESUMED,
            controller.currentBackStackEntry?.lifecycle?.currentState,
        )
        compose.onNodeWithTag("screen:home").assertIsDisplayed()
    }

    @Test
    fun destinationsResumeAndBackFinishesThePreviousDestinationLifecycle() {
        startColdLaunch()

        val homeEntry: NavBackStackEntry = checkNotNull(controller.currentBackStackEntry)

        compose.runOnIdle { tabs.select("library") }
        compose.waitForIdle()

        val libraryEntry = checkNotNull(controller.currentBackStackEntry)
        assertEquals("library", libraryEntry.destination.route)
        assertEquals(Lifecycle.State.RESUMED, libraryEntry.lifecycle.currentState)
        compose.onNodeWithTag("screen:library").assertIsDisplayed()
        // The entry left behind must not stay stuck mid-transition.
        assertFalse(homeEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        compose.runOnIdle { controller.popBackStack() }
        compose.waitForIdle()

        assertEquals("home", controller.currentDestination?.route)
        assertEquals(
            Lifecycle.State.RESUMED,
            controller.currentBackStackEntry?.lifecycle?.currentState,
        )
        // A popped entry is only destroyed when its transition actually completed.
        assertEquals(Lifecycle.State.DESTROYED, libraryEntry.lifecycle.currentState)
        compose.onNodeWithTag("screen:home").assertIsDisplayed()
    }
}
