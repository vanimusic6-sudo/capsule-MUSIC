package com.nikhil.yt.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.nikhil.yt.ui.screens.ScreenTransitions
import org.junit.Assert.assertEquals
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

    @Test fun `no route pairing produces a transition in either direction`() {
        routes.forEach { from ->
            routes.forEach { to ->
                listOf(false, true).forEach { isPop ->
                    assertEquals(
                        "enter $from -> $to (isPop=$isPop) would keep two destinations composed",
                        EnterTransition.None,
                        ScreenTransitions.enter(from, to, isPop),
                    )
                    assertEquals(
                        "exit $from -> $to (isPop=$isPop) would keep two destinations composed",
                        ExitTransition.None,
                        ScreenTransitions.exit(from, to, isPop),
                    )
                }
            }
        }
    }
}
