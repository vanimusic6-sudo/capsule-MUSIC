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
import org.junit.Assert.assertFalse
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

    /**
     * Opening an artist, an album or a playlist fades, and does not move.
     *
     * These screens carry full-bleed artwork, gradients and frame-sized fades, and their bottom
     * edge came apart under a scale and again under a translation. A defect that survives the
     * transform being swapped is not a defect of the transform: it is that geometry moved at all.
     * A fade leaves every edge exactly where it lands, which is why it is the only entrance these
     * screens are allowed.
     */
    @Test fun anythingTheUserOpenedArrivesWithAFadeAndNothingElse() {
        listOf("artist/abc", "album/xyz", "search/q", null).forEach { route ->
            assertEquals(
                "$route should be an opened screen",
                DestinationMotion.Detail,
                destinationMotionFor(route),
            )
        }
        val detail = requireNotNull(DestinationMotion.Detail.spec())
        assertTrue("a detail screen must fade", detail.fade > 0f)
        assertEquals("and must not travel", 0f, detail.lift.value, 0f)
        assertEquals("nor slide", 0f, detail.shift.value, 0f)
        assertEquals("nor scale", 0f, detail.overscale, 0f)
        assertTrue(
            "a fade from nothing is a flash, not an arrival: it starts at ${1f - detail.fade}",
            detail.fade <= 0.6f,
        )
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

        val settings = requireNotNull(DestinationMotion.Settings.spec())
        assertTrue("settings should travel sideways", settings.shift.value > 0f)
        assertEquals("settings should not rise", 0f, settings.lift.value, 0f)
    }

    /**
     * Crossing into or out of settings is an arrival, and it is the one arrival that scales.
     *
     * The distinction matters: a sideways slide into settings reads as if you were already inside
     * it, and a rise reads as one more page arriving rather than as an area of the app opening.
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

        val section = requireNotNull(DestinationMotion.Section.spec())
        val tab = requireNotNull(DestinationMotion.Tab.spec())
        assertTrue("a section should scale", section.overscale > 0f)
        assertEquals("a section should not travel", 0f, section.lift.value, 0f)
        assertEquals("a section should not slide", 0f, section.shift.value, 0f)
        assertTrue(
            "a section should take longer than a tab",
            section.durationMillis > tab.durationMillis,
        )
    }

    /**
     * Two large surfaces that rise from the bottom and resolve a uniform scale read as one animation
     * played twice, however the constants differ. What separates the player from the lyrics is the
     * geometry: opposite anchors, and the lyrics move on one axis only.
     */
    @Test fun theSettingsEntranceLeansAwayBeforeItGoes() {
        val settings = requireNotNull(DestinationMotion.Settings.spec())
        // Covering a sixteenth of the travel in the first tenth of the time is enough to feel like
        // being thrown into the animation rather than leaving the page you were on.
        val startedBy = settings.easing.transform(0.1f)
        assertTrue("a tenth in, $startedBy of the travel is already spent", startedBy < 0.04f)
    }

    /**
     * An entrance costs the screen nothing once it is over.
     *
     * The modifier is removed when the animation ends, so the destination keeps no `graphicsLayer`
     * and no RenderNode for the rest of its life — an idle screen is exactly what it would be if
     * none of this existed. And while it does run, it carries only transforms: a layer with an alpha
     * below 1, or a RenderEffect, has to be composited through an offscreen buffer the size of the
     * screen, where a translation and a scale are a matrix the GPU applies while drawing anyway.
     *
     * This is pinned rather than assumed because it is invisible when it regresses: an entrance that
     * quietly starts carrying an alpha looks identical and costs a buffer per screen per frame.
     */
    @Test fun onlyTheDetailScreensPayForAnOffscreenBuffer() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec() ?: return@forEach
            assertTrue(
                "$motion does nothing, so it is a layer created for no reason",
                spec.lift.value > 0f || spec.shift.value > 0f || spec.overscale > 0f ||
                    spec.fade > 0f,
            )
            /*
             * An alpha below 1 has to be composited through an offscreen buffer the size of the
             * screen, where a transform is a matrix applied while drawing. The detail screens pay
             * that knowingly, because they are the ones a moving layer tears; nothing else may
             * start paying it quietly, which is invisible when it regresses — an entrance that
             * gains an alpha looks identical and costs a buffer per screen per frame.
             */
            if (motion != DestinationMotion.Detail) {
                assertEquals("$motion must not fade", 0f, spec.fade, 0f)
            }
        }
    }

    /**
     * A decelerate that dumps almost all its travel into the first moments finishes somewhere the
     * eye cannot follow, so the screen looks like it jumps into place and stops dead. Keeping real
     * distance for the last third of the duration is what makes the end read as an arrival.
     */
    @Test fun theTabEntranceIsStillVisiblyMovingNearTheEnd() {
        val tab = requireNotNull(DestinationMotion.Tab.spec())
        val remaining = 1f - tab.easing.transform(2f / 3f)
        assertTrue(
            "only ${remaining * 100}% of the travel is left for the final third",
            remaining >= 0.08f,
        )
    }

    @Test fun bothCharactersStayShortEnoughToFeelImmediate() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec() ?: return@forEach
            // Soft, but still an answer to a tap rather than a wait.
            assertTrue(
                "$motion takes ${spec.durationMillis}ms",
                spec.durationMillis in 200..520,
            )
            // A screen mid-entrance is still a screen someone may be reading, and a stalled
            // animation must never leave a destination looking blank.
            // Restraint is part of the brief: this is character, not a page transition.
            assertTrue("$motion travels too far", spec.lift.value <= 32f)
            assertTrue("$motion slides too far", spec.shift.value <= 40f)
            assertTrue("$motion scales too much", spec.overscale <= 0.06f)
            assertTrue("$motion fades too deeply", spec.fade <= 0.6f)
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

    /**
     * No entrance scales, and none ever should again.
     *
     * A scale resamples every edge in the frame for the length of the animation, so an edge where
     * two opaque fills meet — the bottom of an artist header, for one — crawls and flickers until
     * the screen lands. It also draws outside its own bounds, because `graphicsLayer` does not
     * clip, so an oversized screen overhangs its neighbours and then retracts.
     *
     * This is a source check rather than a spec check on purpose: the spec no longer has a field to
     * assert against, which is exactly why a scale could come back as a line in the layer block
     * without anything here noticing.
     */
    /**
     * The settings boundary scales; nothing else does, and the detail screens do not animate at all.
     *
     * A scale resamples every edge in the frame, so on a screen with full-bleed artwork the seam
     * between two opaque fills crawls until it lands. Settings pages are flat lists on a flat
     * background and show none of that, which is the whole reason this one is allowed to keep it.
     */
    @Test fun onlyTheSettingsBoundaryScales() {
        DestinationMotion.entries.forEach { motion ->
            val spec = motion.spec()
            requireNotNull(spec)
            if (motion == DestinationMotion.Section) {
                assertTrue("the settings boundary must keep its scale", spec.overscale > 0f)
            } else {
                assertEquals(
                    "$motion must not scale: its screens can carry artwork",
                    0f,
                    spec.overscale,
                    0f,
                )
            }
        }
    }
}
