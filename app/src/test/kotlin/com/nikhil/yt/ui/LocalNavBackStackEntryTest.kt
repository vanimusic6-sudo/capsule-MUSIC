package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.screens.LocalNavBackStackEntry
import com.nikhil.yt.ui.screens.routeComposable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen must see its own entry, and must not be recomposed by navigation happening elsewhere.
 *
 * Observing `currentBackStackEntryAsState()` broke both: every screen woke up on every navigation,
 * including the one being torn down, and each of them read whichever destination happened to be
 * current rather than their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class LocalNavBackStackEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController
    private val seenBy = mutableMapOf<String, NavBackStackEntry?>()
    private val compositionsOf = mutableMapOf<String, Int>()

    private fun showGraph() {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                NavHost(controller, startDestination = "first", modifier = Modifier.fillMaxSize()) {
                    listOf("first", "second", "third").forEach { route ->
                        routeComposable(route) {
                            seenBy[route] = LocalNavBackStackEntry.current
                            compositionsOf[route] = (compositionsOf[route] ?: 0) + 1
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aScreenSeesItsOwnEntryRatherThanWhicheverIsCurrent() {
        showGraph()
        assertEquals(controller.currentBackStackEntry, seenBy["first"])

        compose.runOnIdle { controller.navigate("second") }
        compose.waitForIdle()

        assertEquals(controller.currentBackStackEntry, seenBy["second"])
        // The first screen's entry is still its own; it did not follow the navigation.
        assertEquals("first", seenBy["first"]?.destination?.route)
    }

    @Test fun navigationElsewhereNeverWakesAScreenThatIsAlreadyGone() {
        showGraph()

        compose.runOnIdle { controller.navigate("second") }
        compose.waitForIdle()
        val firstAfterLeaving = compositionsOf["first"] ?: 0

        // Churn between two other destinations. A screen that observes the globally current entry
        // recomposes on every one of these; a screen that reads its own entry does not move at all.
        // That matters beyond the wasted work: such a recomposition re-resolves the gone screen's
        // ViewModels, and once its entry is destroyed that is the reported crash.
        repeat(6) {
            compose.runOnIdle { controller.navigate("third") }
            compose.waitForIdle()
            compose.runOnIdle { controller.navigate("second") }
            compose.waitForIdle()
        }

        assertEquals(
            "the screen that was left recomposed during unrelated navigation",
            firstAfterLeaving,
            compositionsOf["first"] ?: 0,
        )
        assertEquals("second", controller.currentDestination?.route)
        assertTrue("the graph stopped composing entirely", (compositionsOf["third"] ?: 0) > 0)
    }

    @Test fun anEntryHandedToAScreenIsAliveForAsLongAsThatScreenIsComposed() {
        showGraph()
        val entry = checkNotNull(seenBy["first"])
        assertTrue(entry.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))

        compose.runOnIdle { controller.navigate("second") }
        compose.waitForIdle()

        // "first" is no longer composed, so whatever happens to its entry cannot reach a live
        // composition — which is the property that makes the crash unreachable.
        assertEquals("second", controller.currentDestination?.route)
        assertEquals(controller.currentBackStackEntry, seenBy["second"])
    }
}
