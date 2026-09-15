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
    private val tabRoutes: Set<String> = Screens.MainScreens.mapTo(mutableSetOf()) { it.route }

    fun select(route: String, onReselected: () -> Unit = {}) {
        // Null until NavHost has set the graph. Bailing out keeps a tap that races the first frame
        // (or arrives during state restoration) from reading a graph that does not exist yet.
        val currentEntry = navController.currentBackStackEntry ?: return

        if (currentEntry.destination.route == route) {
            onReselected()
            return
        }

        leaveAnythingOpenedOnTopOfTheTabs()

        // Stripping may already have landed on the tab that was asked for — it was underneath all
        // along. Navigating again would only save and restore state for no reason.
        if (navController.currentDestination?.route == route) return

        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * Pops artists, playlists, settings pages — anything that is not a tab — before switching.
     *
     * This is what makes a tab button mean the tab. `saveState`/`restoreState` are worth keeping:
     * they are why a tab still has its scroll position and its ViewModels when you come back to it.
     * But in a flat graph they save *everything stacked above the start destination*, which is not
     * "this tab's own stack" — it is whatever the user happened to have open. So a tap on Home
     * saved the settings page under Home's key and then restored it in the same call, leaving the
     * user exactly where they were; and a tap on Library after opening an artist saved that artist,
     * so the next tap on Home brought the artist back instead of Home.
     *
     * Popping those entries first, and deliberately *without* saving them, means the saved stacks
     * only ever contain tabs. A tab tap can then restore a tab and nothing else.
     *
     * The pops happen in one synchronous burst, so no intermediate destination is ever composed —
     * Compose recomposes once per frame, and only the final state reaches it.
     */
    private fun leaveAnythingOpenedOnTopOfTheTabs() {
        while (navController.currentDestination?.route !in tabRoutes) {
            // False once there is nothing left to pop, which also stops this at the graph root if
            // the start destination is somehow not one of the tabs.
            if (!navController.popBackStack()) return
        }
    }
}
