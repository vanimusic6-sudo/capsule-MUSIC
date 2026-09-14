package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.screens.DestinationMotion
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.screens.destinationMotionFor
import com.nikhil.yt.ui.screens.routeComposable
import com.nikhil.yt.ui.screens.spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A destination's entrance is character, never a gate on seeing the screen.
 *
 * The failure that matters here is an entrance that does not finish: the content would sit at its
 * starting opacity forever and the destination would read as broken rather than soft. An earlier
 * attempt at route motion shipped exactly that, so completion and layout-neutrality are pinned,
 * not assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class DestinationEntranceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController

    private fun showGraph() {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                NavHost(controller, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                    listOf("home", "library", "artist/abc", "settings/appearance").forEach { route ->
                        routeComposable(route) {
                            Box(Modifier.fillMaxSize().testTag("screen:$route"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theBottomBarDestinationsUseTheTabCharacter() {
        Screens.MainScreens.forEach { screen ->
            assertEquals(
                "${screen.route} should move like a tab",
                DestinationMotion.Tab,
                destinationMotionFor(screen.route),
            )
        }
    }

    @Test fun anythingTheUserOpenedUsesTheDetailCharacter() {
        listOf("artist/abc", "album/xyz", "settings", "settings/appearance", "search/q", null)
            .forEach { route ->
                assertEquals(
                    "$route should settle like an opened screen",
                    DestinationMotion.Detail,
                    destinationMotionFor(route),
                )
            }
    }

    @Test fun bothCharactersStayShortEnoughToFeelImmediate() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            assertTrue(
                "$motion takes ${spec.durationMillis}ms",
                spec.durationMillis in 120..340,
            )
            // A screen mid-entrance is still a screen someone may be reading, and a stalled
            // animation must never leave a destination looking blank.
            assertTrue("$motion starts too faint at ${spec.fromAlpha}", spec.fromAlpha >= 0.4f)
            assertTrue("$motion starts opaque", spec.fromAlpha < 1f)
            // Restraint is part of the brief: this is character, not a page transition.
            assertTrue("$motion travels too far", spec.lift.value <= 16f)
            assertTrue("$motion scales too much", spec.overscale <= 0.06f)
        }
    }

    @Test fun everyDestinationIsFullyVisibleOnceItsEntranceHasRun() {
        showGraph()
        compose.onNodeWithTag("screen:home").assertIsDisplayed()

        listOf("library", "artist/abc", "settings/appearance").forEach { route ->
            compose.runOnIdle { controller.navigate(route) }
            compose.waitForIdle()
            compose.onNodeWithTag("screen:$route").assertIsDisplayed()
        }
    }

    @Test fun theEntranceMovesOnlyTheDrawingAndLandsExactlyInPlace() {
        compose.mainClock.autoAdvance = false
        showGraph()

        // Read eagerly: a SemanticsNode computes its bounds when they are asked for, so holding the
        // node and reading it later would sample the settled state twice and pass regardless.
        fun snapshot() =
            compose.onNodeWithTag("screen:home").fetchSemanticsNode()
                .let { it.size to it.boundsInRoot.top }

        val (sizeDuring, topDuring) = snapshot()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val (sizeSettled, topSettled) = snapshot()

        // Measurement is untouched, so the slot a screen occupies never changes and an entrance
        // cannot disturb whatever sits beside it.
        assertEquals(sizeDuring, sizeSettled)

        // The drawing does move — that is the point — but it has to finish exactly at the layout
        // position rather than leaving the screen permanently offset.
        assertTrue("the entrance did not travel at all", topDuring > topSettled)
        assertEquals(0f, topSettled, 0.01f)
    }
}
