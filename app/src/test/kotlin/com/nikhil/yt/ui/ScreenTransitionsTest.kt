package com.nikhil.yt.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.nikhil.yt.ui.screens.DestinationMotion
import com.nikhil.yt.ui.screens.RouteDirection
import com.nikhil.yt.ui.screens.RouteHistory
import com.nikhil.yt.ui.screens.ScreenTransitions
import com.nikhil.yt.ui.screens.destinationMotionFor
import com.nikhil.yt.ui.screens.routeDirection
import com.nikhil.yt.ui.screens.spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route changes must stay instant, in both directions.
 *
 * This is not a style preference to be relaxed later. A timed route transition keeps both
 * destinations composed for its whole duration, and that window is what let a popped destination
 * recompose after its NavBackStackEntry was destroyed — the ViewModel IllegalStateException seen on
 * device under rapid tab switching. It is also what made switching hitch and made each tab feel like
 * it moved at a different speed.
 *
 * And an exit transition is, by construction, one screen drawn over another: a fade means the screen
 * behind shows through the one leaving, and a slide means they occupy the screen together. That is
 * the single effect this app does not want anywhere, so no route pairing gets one — not even leaving
 * settings, which used to be the one exception.
 *
 * What leaving settings gets instead is direction: the page you return to arrives from the edge you
 * left by. One screen moves, one screen is on display.
 */
class ScreenTransitionsTest {
    private val routes = listOf(
        null, "home", "library", "history", "stats",
        "settings", "settings/appearance", "settings/appearance/palette_picker",
        "artist/abc", "album/xyz", "search/query",
    )

    @Test fun `no route pairing ever produces a transition in either direction`() {
        routes.forEach { from ->
            routes.forEach { to ->
                listOf(false, true).forEach { isPop ->
                    assertEquals(
                        "enter $from -> $to (isPop=$isPop) would keep two destinations composed",
                        EnterTransition.None,
                        ScreenTransitions.enter(from, to, isPop),
                    )
                    assertEquals(
                        "exit $from -> $to (isPop=$isPop) would draw one screen over another",
                        ExitTransition.None,
                        ScreenTransitions.exit(from, to, isPop),
                    )
                }
            }
        }
    }

    @Test fun `stepping back out of settings arrives from the leading edge`() {
        assertEquals(
            RouteDirection.Backward,
            routeDirection(from = "settings/appearance", to = "settings"),
        )
        assertEquals(
            RouteDirection.Backward,
            routeDirection(from = "settings/appearance/palette_picker", to = "settings/appearance"),
        )
    }

    @Test fun `going deeper into settings arrives from the trailing edge`() {
        assertEquals(
            RouteDirection.Forward,
            routeDirection(from = "settings", to = "settings/appearance"),
        )
    }

    @Test fun `a sibling page is an arrival, not a step back`() {
        assertEquals(
            RouteDirection.Forward,
            routeDirection(from = "settings/appearance", to = "settings/content"),
        )
    }

    @Test fun `a route that merely starts with another is not inside it`() {
        // Without a separator check, "settings_backup" would count as a child of "settings" and the
        // page would travel the wrong way.
        assertEquals(
            RouteDirection.Forward,
            routeDirection(from = "settings_backup", to = "settings"),
        )
    }

    @Test fun `the first destination of a session has nothing to step back from`() {
        assertEquals(RouteDirection.Forward, routeDirection(from = null, to = "home"))
    }

    @Test fun `history reports the direction of each arrival in turn`() {
        val history = RouteHistory()

        assertEquals(RouteDirection.Forward, history.enter("home").direction)
        assertEquals(RouteDirection.Forward, history.enter("settings").direction)
        assertEquals(RouteDirection.Forward, history.enter("settings/appearance").direction)
        // Back out of the tree, one step at a time.
        assertEquals(RouteDirection.Backward, history.enter("settings").direction)
        assertEquals(RouteDirection.Forward, history.enter("home").direction)
    }

    @Test fun `history remembers what each destination was reached from`() {
        val history = RouteHistory()

        assertEquals(null, history.enter("home").from)
        assertEquals("home", history.enter("settings").from)
        assertEquals("settings", history.enter("settings/appearance").from)
    }

    /**
     * The route alone does not say what a navigation means, and taking the motion from the
     * destination alone got both ends of the settings tree wrong: opening settings slid sideways as
     * if it were already one of its own pages, and closing settings made the screen behind rise like
     * a library tab.
     */
    @Test fun `entering and leaving settings both cross a boundary`() {
        assertEquals(
            "opening settings is an arrival, not a step along its path",
            DestinationMotion.Section,
            destinationMotionFor("settings", from = "home"),
        )
        assertEquals(
            "closing settings is an area closing, not a tab switch",
            DestinationMotion.Section,
            destinationMotionFor("home", from = "settings"),
        )
        assertEquals(
            DestinationMotion.Section,
            destinationMotionFor("library", from = "settings/appearance"),
        )
    }

    @Test fun `moving inside settings is a step along a path`() {
        assertEquals(
            DestinationMotion.Settings,
            destinationMotionFor("settings/appearance", from = "settings"),
        )
        assertEquals(
            DestinationMotion.Settings,
            destinationMotionFor("settings", from = "settings/appearance"),
        )
        assertEquals(
            DestinationMotion.Settings,
            destinationMotionFor("settings/content", from = "settings/appearance"),
        )
    }

    @Test fun `a tab reached from another tab still moves like a tab`() {
        assertEquals(DestinationMotion.Tab, destinationMotionFor("home", from = "library"))
        assertEquals(DestinationMotion.Tab, destinationMotionFor("stats", from = "home"))
        // Cold start: nothing was on screen before, so nothing was crossed.
        assertEquals(DestinationMotion.Tab, destinationMotionFor("home", from = null))
    }

    @Test fun `a route that merely starts with settings is outside the tree`() {
        // Otherwise opening it from settings would look like a step inside it.
        assertEquals(
            DestinationMotion.Section,
            destinationMotionFor("settings_backup", from = "settings"),
        )
    }

    /**
     * Retracing a step should not take as long as taking it.
     */
    @Test fun `stepping back out of a settings page is quicker than stepping in`() {
        val settings = DestinationMotion.Settings.spec()
        assertTrue(
            "back takes ${settings.backwardDurationMillis}ms against ${settings.durationMillis}ms",
            settings.backwardDurationMillis < settings.durationMillis,
        )
        // But not so much quicker that the pair stops reading as one movement reversed.
        assertTrue(
            "back is ${settings.backwardDurationMillis}ms, too far from ${settings.durationMillis}ms",
            settings.backwardDurationMillis >= settings.durationMillis * 3 / 4,
        )
    }

    @Test fun `motions with no direction take the same time either way`() {
        listOf(DestinationMotion.Tab, DestinationMotion.Detail, DestinationMotion.Section)
            .forEach { motion ->
                val spec = motion.spec()
                assertEquals(
                    "$motion should not change speed with direction",
                    spec.durationMillis,
                    spec.backwardDurationMillis,
                )
            }
    }
}
