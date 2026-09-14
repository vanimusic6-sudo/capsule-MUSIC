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
        listOf("artist/abc", "album/xyz", "search/q", null).forEach { route ->
            assertEquals(
                "$route should settle like an opened screen",
                DestinationMotion.Detail,
                destinationMotionFor(route),
            )
        }
    }

    @Test fun settingsPagesMoveSidewaysBecauseTheyAreOneStructure() {
        listOf("settings", "settings/appearance", "settings/appearance/palette_picker")
            .forEach { route ->
                assertEquals(
                    "$route should step along a path, not be presented",
                    DestinationMotion.Settings,
                    destinationMotionFor(route),
                )
            }

        val settings = DestinationMotion.Settings.spec()
        assertTrue("settings should travel sideways", settings.shift.value > 0f)
        assertEquals("settings should not scale", 0f, settings.overscale, 0f)
    }

    /**
     * Route transitions are None, so the screen being left disappears at once. An arriving screen
     * that started faint would leave those first frames showing neither screen properly — a flash
     * of bare canvas, which is what reads as a flicker and as harshness. Character has to come from
     * movement, not from fading up out of nothing.
     */
    @Test fun noEntranceStartsFaintEnoughToFlashTheBareCanvas() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            assertTrue(
                "$motion starts at ${spec.fromAlpha}, which would dip to the canvas",
                spec.fromAlpha >= 0.8f,
            )
            assertTrue("$motion starts opaque", spec.fromAlpha < 1f)
        }
    }

    /**
     * A decelerate that dumps almost all its travel into the first moments finishes somewhere the
     * eye cannot follow, so the screen looks like it jumps into place and stops dead. Keeping real
     * distance for the last third of the duration is what makes the end read as an arrival.
     */
    @Test fun theTabEntranceIsStillVisiblyMovingNearTheEnd() {
        val tab = DestinationMotion.Tab.spec()
        val remaining = 1f - tab.easing.transform(2f / 3f)
        assertTrue(
            "only ${remaining * 100}% of the travel is left for the final third",
            remaining >= 0.08f,
        )
    }

    @Test fun bothCharactersStayShortEnoughToFeelImmediate() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            // Soft, but still an answer to a tap rather than a wait.
            assertTrue(
                "$motion takes ${spec.durationMillis}ms",
                spec.durationMillis in 200..460,
            )
            // A screen mid-entrance is still a screen someone may be reading, and a stalled
            // animation must never leave a destination looking blank.
            // Restraint is part of the brief: this is character, not a page transition.
            assertTrue("$motion travels too far", spec.lift.value <= 18f)
            assertTrue("$motion slides too far", spec.shift.value <= 40f)
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
