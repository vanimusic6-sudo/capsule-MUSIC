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
import com.nikhil.yt.ui.screens.resetNavigationHistory
import com.nikhil.yt.ui.screens.routeComposable
import com.nikhil.yt.ui.screens.spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * The route history is process-wide, so without this a test inherits wherever the previous one
     * left off — and since the motion now depends on what a destination was reached *from*, that
     * silently changes what is being measured.
     */
    @Before fun startFromNowhere() = resetNavigationHistory()

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
        listOf("settings/appearance", "settings/appearance/palette_picker")
            .forEach { route ->
                assertEquals(
                    "$route should step along a path, not be presented",
                    DestinationMotion.Settings,
                    destinationMotionFor(route, from = "settings"),
                )
            }

        val settings = DestinationMotion.Settings.spec()
        assertTrue("settings should travel sideways", settings.shift.value > 0f)
        assertEquals("settings should not scale", 0f, settings.overscale, 0f)
    }

    /**
     * Crossing into or out of settings is an arrival, and arrivals scale. The distinction matters:
     * a sideways slide into settings reads as if you were already inside it, and a tab's lift on
     * the way out reads as the library rather than as settings closing.
     */
    @Test fun crossingTheSettingsBoundaryIsAnArrival() {
        listOf(
            "settings" to "home",
            "settings/appearance" to "library",
            "home" to "settings",
            "library" to "settings/appearance",
        ).forEach { (route, from) ->
            assertEquals(
                "$from -> $route crosses the boundary",
                DestinationMotion.Section,
                destinationMotionFor(route, from = from),
            )
        }

        val section = DestinationMotion.Section.spec()
        val detail = DestinationMotion.Detail.spec()
        assertTrue("a section should scale, like opening an artist", section.overscale > 0f)
        assertEquals("a section should not travel", 0f, section.lift.value, 0f)
        assertEquals("a section should not slide", 0f, section.shift.value, 0f)
        // A whole area of the app is a bigger thing to arrive at than one artist.
        assertTrue("a section should scale more than a detail", section.overscale > detail.overscale)
        assertTrue(
            "a section should take longer than a detail",
            section.durationMillis > detail.durationMillis,
        )
    }

    /**
     * Two large surfaces that rise from the bottom and resolve a uniform scale read as one animation
     * played twice, however the constants differ. What separates the player from the lyrics is the
     * geometry: opposite anchors, and the lyrics move on one axis only.
     */
    @Test fun theSettingsEntranceLeansAwayBeforeItGoes() {
        val settings = DestinationMotion.Settings.spec()
        // Covering a sixteenth of the travel in the first tenth of the time is enough to feel like
        // being thrown into the animation rather than leaving the page you were on.
        val startedBy = settings.easing.transform(0.1f)
        assertTrue("a tenth in, $startedBy of the travel is already spent", startedBy < 0.04f)
    }

    /**
     * Route transitions are None, so the screen being left disappears at once, and for a moment the
     * arriving screen is all there is. It used to arrive *semi-transparent*, which showed the bare
     * canvas through it and read as a washed-out flash — and worse, the layer alpha multiplied into
     * every child, which is what made a half-transparent card travel a different colour curve from
     * its neighbours.
     *
     * Content is now fully opaque from the first frame and a veil is drawn on top instead. The veil
     * has to be brief and light: it is there to mask content still settling, not to be noticed.
     */
    @Test fun everyEntranceArrivesOpaqueUnderABriefVeil() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            assertTrue("$motion has no veil at all", spec.scrim > 0f)
            assertTrue(
                "$motion starts at ${spec.scrim}, dark enough to read as a blackout",
                spec.scrim <= 0.28f,
            )
            assertTrue(
                "$motion keeps its veil for ${spec.scrimWindow} of the entrance",
                spec.scrimWindow in 0.15f..0.5f,
            )
        }
    }

    /**
     * The veil masks a moment; the movement is the character. If it outlasted the motion it would
     * become the character, and a screen that darkens on every navigation is a screen that flickers.
     */
    @Test fun noVeilOutlastsTheMovementItCovers() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            val liftsAfterMillis = spec.durationMillis * spec.scrimWindow
            assertTrue(
                "$motion is still dark ${liftsAfterMillis}ms in",
                liftsAfterMillis <= 220f,
            )
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
                spec.durationMillis in 200..520,
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
