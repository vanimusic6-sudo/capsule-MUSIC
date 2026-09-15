package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.utils.liveSavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
 * Seven screens observed `currentBackStackEntryAsState()` — which reports whichever entry is
 * globally current, not the screen's own — and read `savedStateHandle` off it to pick up a
 * "scroll to top" request. During rapid navigation that value briefly refers to an entry that has
 * just been popped and destroyed, and the recomposition that follows kills the app.
 *
 * The first test proves the hazard is real by reaching for the raw accessor on a destroyed entry:
 * it must throw. The rest prove the guarded accessor makes the same call harmless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class DestroyedEntrySavedStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController

    private fun showGraph() {
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = "first") {
                composable("first") {}
                composable("second") {}
            }
        }
        compose.waitForIdle()
    }

    private fun destroyedEntry(): NavBackStackEntry {
        showGraph()
        compose.runOnIdle { controller.navigate("second") }
        compose.waitForIdle()
        val second = checkNotNull(controller.currentBackStackEntry)
        compose.runOnIdle { controller.popBackStack() }
        compose.waitForIdle()
        assertEquals(Lifecycle.State.DESTROYED, second.lifecycle.currentState)
        return second
    }

    @Test fun reachingIntoADestroyedEntryIsWhatCrashedTheApp() {
        val destroyed = destroyedEntry()

        // The reported crash, verbatim: resolving a ViewModel from a destroyed entry. A screen that
        // recomposes after its entry is gone calls hiltViewModel() and lands exactly here.
        val fromViewModels = assertThrows(IllegalStateException::class.java) {
            destroyed.viewModelStore
        }
        assertEquals(
            "You cannot access the NavBackStackEntry's ViewModels after the " +
                "NavBackStackEntry is destroyed.",
            fromViewModels.message,
        )

        // Saved state is the same hazard through a different door, and it is the one the screens
        // reached for on every navigation.
        assertThrows(IllegalStateException::class.java) { destroyed.savedStateHandle }
    }

    @Test fun theGuardedAccessorReturnsNullInsteadOfThrowing() {
        val destroyed = destroyedEntry()
        assertNull(destroyed.liveSavedStateHandle())
        // Repeated reads, the way a recomposition loop would do it, stay harmless.
        repeat(5) { assertNull(destroyed.liveSavedStateHandle()) }
    }

    @Test fun theGuardedAccessorStillWorksOnALiveEntry() {
        showGraph()
        val live = checkNotNull(controller.currentBackStackEntry)
        val handle = checkNotNull(live.liveSavedStateHandle())
        handle["scrollToTop"] = true
        assertEquals(true, handle.get<Boolean>("scrollToTop"))
    }

    @Test fun aFlowTakenFromALiveEntrySurvivesThatEntryBeingDestroyed() {
        showGraph()
        compose.runOnIdle { controller.navigate("second") }
        compose.waitForIdle()
        val second = checkNotNull(controller.currentBackStackEntry)
        // Screens hold on to this flow across navigation, so collecting it afterwards must be safe
        // even though reaching for the handle again would not be.
        val flow = checkNotNull(second.liveSavedStateHandle()).getStateFlow("scrollToTop", false)

        compose.runOnIdle { controller.popBackStack() }
        compose.waitForIdle()

        assertEquals(Lifecycle.State.DESTROYED, second.lifecycle.currentState)
        assertEquals(false, flow.value)
        assertNull(second.liveSavedStateHandle())
    }

    @Test fun composeNavigatorIsStillAttachedAfterAllOfThis() {
        showGraph()
        val navigator = controller.navigatorProvider.getNavigator(ComposeNavigator::class.java)
        assertEquals(true, navigator.isAttached)
    }
}
