package com.nikhil.yt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController

@Composable
internal fun rememberMainTabNavigator(navController: NavHostController): MainTabNavigator =
    remember(navController) { MainTabNavigator(navController) }

/**
 * Bottom-bar tab selection, expressed as the ordinary Navigation Compose bottom-navigation pattern.
 *
 * This class deliberately owns no state, no coroutines and no navigator internals. Two earlier
 * attempts at being clever here caused production defects, and both are the reason this is now
 * plain:
 *
 * 1. Reading `ComposeNavigator.backStack` / calling `onTransitionComplete` from a composition-scoped
 *    effect. `NavHost` lives inside the `Scaffold` content, which Material 3 subcomposes during the
 *    measure pass, so effects declared next to [rememberMainTabNavigator] run *before* `NavHost`
 *    assigns `navController.graph`. Touching navigator state in that window throws
 *    `IllegalStateException: You cannot access the Navigator's state until the Navigator is attached`
 *    on every cold start. `NavHost` completes its own transitions; nothing here has to help it.
 *
 * 2. Deferring the actual `navigate` call into a `withFrameNanos` callback to coalesce same-frame
 *    taps. Mutating the back stack from inside a frame callback means the entering destination's
 *    transition is never given an animation frame, and an interleaved Back then leaves `NavHost`
 *    with an orphaned transition: the destination stays at `STARTED` instead of `RESUMED` and the
 *    popped entry is never destroyed. Compose already coalesces a same-frame tap burst for free,
 *    because it recomposes once per frame: intermediate entries are created and popped without ever
 *    being composed, so no destination work is wasted.
 */
internal class MainTabNavigator(private val navController: NavHostController) {
    fun select(route: String, onReselected: () -> Unit = {}) {
        // Null until NavHost has set the graph. Bailing out keeps a tap that races the first frame
        // (or arrives during state restoration) from reading a graph that does not exist yet.
        val currentEntry = navController.currentBackStackEntry ?: return

        if (currentEntry.destination.route == route) {
            onReselected()
            return
        }

        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
