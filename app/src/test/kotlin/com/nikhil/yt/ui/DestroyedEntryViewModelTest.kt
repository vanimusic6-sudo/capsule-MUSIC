package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.screens.MainTabNavigator
import com.nikhil.yt.ui.screens.ScreenTransitions
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.rememberMainTabNavigator
import com.nikhil.yt.ui.screens.routeComposable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the device crash
 * `IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the
 * NavBackStackEntry is destroyed`.
 *
 * Every destination here owns a ViewModel, exactly as the real screens do. A destination that is
 * still composed after its entry has been destroyed will resolve one and throw, so this reproduces
 * the failure through the same route the app takes rather than asserting on timings.
 *
 * The burst is deliberately harder than a person can tap. That is the case that crashed: a route
 * transition kept the outgoing destination composed long after it was popped, and rapid switching
 * destroys entries while they are still on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class DestroyedEntryViewModelTest {
    class ScreenViewModel : ViewModel()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController
    private lateinit var tabs: MainTabNavigator

    /** Read by every destination, so bumping it forces anything still composed to recompose. */
    private val recomposeTrigger = androidx.compose.runtime.mutableIntStateOf(0)

    private val tabRoutes = Screens.MainScreens.map { it.route }

    private fun showTabs() {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                tabs = rememberMainTabNavigator(controller)
                NavHost(
                    controller,
                    startDestination = tabRoutes.first(),
                    modifier = Modifier.fillMaxSize(),
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
                    tabRoutes.forEach { route ->
                        routeComposable(route) {
                            // Resolving a ViewModel from a destroyed entry is the crash, and
                            // viewModel() reaches the entry's store on every composition.
                            viewModel<ScreenViewModel>()
                            val tick = recomposeTrigger.intValue
                            Box(Modifier.fillMaxSize().testTag("screen:$route:$tick"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aFranticTabBurstNeverResolvesAViewModelFromADestroyedEntry() {
        // The clock has to be pinned. Left to auto-advance it settles every transition instantly,
        // which is precisely the window this test exists to open, so the test would pass against
        // the very code that crashes on device.
        compose.mainClock.autoAdvance = false
        showTabs()

        repeat(12) { round ->
            tabRoutes.forEach { route ->
                compose.runOnIdle { tabs.select(route) }
                // Step a fraction of a transition: entries are destroyed part-way through one
                // rather than only at settled points.
                compose.mainClock.advanceTimeBy(if (round % 3 == 0) 8L else 40L)
            }
            // Force every composed destination to recompose. One that outlived its entry resolves
            // a ViewModel from a destroyed store here and throws.
            compose.runOnIdle { recomposeTrigger.intValue += 1 }
            compose.mainClock.advanceTimeBy(16L)
        }

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertTrue(
            "navigation did not survive the burst",
            controller.currentDestination?.route in tabRoutes,
        )
        assertEquals(
            Lifecycle.State.RESUMED,
            controller.currentBackStackEntry?.lifecycle?.currentState,
        )
    }

    @Test fun onlyTheCurrentDestinationIsComposedOnceASwitchHasSettled() {
        showTabs()

        tabRoutes.drop(1).forEach { route ->
            compose.runOnIdle { tabs.select(route) }
            compose.waitForIdle()

            // A destination left composed after its entry is gone is the whole hazard, so nothing
            // but the current route may still be on screen.
            tabRoutes.filter { it != route }.forEach { other ->
                assertEquals(
                    "$other is still composed while $route is current",
                    0,
                    compose.onAllNodesWithTag("screen:$other:${recomposeTrigger.intValue}")
                        .fetchSemanticsNodes().size,
                )
            }
        }
    }

    @Test fun aPoppedEntryIsFullyDestroyedRatherThanLeftAlive() {
        showTabs()

        compose.runOnIdle { tabs.select(tabRoutes[1]) }
        compose.waitForIdle()
        val left: NavBackStackEntry = checkNotNull(controller.currentBackStackEntry)

        compose.runOnIdle { controller.popBackStack() }
        compose.waitForIdle()

        assertEquals(Lifecycle.State.DESTROYED, left.lifecycle.currentState)
        assertEquals(
            "the popped destination is still composed",
            0,
            compose.onAllNodesWithTag("screen:${tabRoutes[1]}:${recomposeTrigger.intValue}")
                .fetchSemanticsNodes().size,
        )
    }
}
