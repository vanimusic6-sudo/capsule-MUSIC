package com.nikhil.yt.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.nikhil.yt.ui.screens.ScreenTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route changes must stay instant.
 *
 * This is not a style preference to be relaxed later. A timed route transition keeps both
 * destinations composed for its whole duration, and that window is what let a popped destination
 * recompose after its NavBackStackEntry was destroyed — the ViewModel IllegalStateException seen on
 * device under rapid tab switching. It is also what made switching hitch and made each tab feel
 * like it moved at a different speed.
 *
 * So the contract is pinned here: whatever the route, and whichever direction, there is no
 * transition. Overlap is handled by every destination owning an opaque canvas instead.
 */
class ScreenTransitionsTest {
    private val routes = listOf(
        null, "home", "library", "history", "stats",
        "settings", "settings/appearance", "settings/appearance/palette_picker",
        "artist/abc", "album/xyz", "search/query",
    )

    @Test fun `no route pairing ever produces an enter transition`() {
        routes.forEach { from ->
            routes.forEach { to ->
                listOf(false, true).forEach { isPop ->
                    assertEquals(
                        "enter $from -> $to (isPop=$isPop) would keep two destinations composed",
                        EnterTransition.None,
                        ScreenTransitions.enter(from, to, isPop),
                    )
                }
            }
        }
    }

    @Test fun `only leaving settings costs an exit transition`() {
        // Everything else still costs exactly one composed screen per navigation.
        val unaffected = routes.filterNot { it?.startsWith("settings") == true }
        unaffected.forEach { from ->
            routes.forEach { to ->
                listOf(false, true).forEach { isPop ->
                    assertEquals(
                        "exit $from -> $to (isPop=$isPop) would keep two destinations composed",
                        ExitTransition.None,
                        ScreenTransitions.exit(from, to, isPop),
                    )
                }
            }
        }
    }

    @Test fun `stepping back out of settings is animated`() {
        // Both ways out: back to a shallower settings page, and out of settings entirely.
        assertNotEquals(
            ExitTransition.None,
            ScreenTransitions.exit("settings/appearance", "settings", isPop = true),
        )
        assertNotEquals(
            ExitTransition.None,
            ScreenTransitions.exit("settings", "home", isPop = true),
        )
    }

    @Test fun `going deeper into settings is left to the arriving screen`() {
        // The destination plays its own entrance, so the page being left does not need to move and
        // the pair does not need to be composed together.
        assertEquals(
            ExitTransition.None,
            ScreenTransitions.exit("settings", "settings/appearance"),
        )
    }

    @Test fun `the settings exit stays short enough to keep the overlap brief`() {
        assertTrue(
            "the settings exit takes ${ScreenTransitions.SettingsExitMillis}ms",
            ScreenTransitions.SettingsExitMillis in 160..320,
        )
    }
}
