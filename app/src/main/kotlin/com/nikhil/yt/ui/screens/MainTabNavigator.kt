package com.nikhil.yt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberMainTabNavigator(navController: NavHostController): MainTabNavigator {
    val scope = rememberCoroutineScope()
    return remember(navController, scope) { MainTabNavigator(navController, scope) }
}

/**
 * Coalesce only taps that land before the next UI frame; never wait for an animation to finish.
 *
 * The old implementation waited for Lifecycle.RESUMED, which created a very noticeable cooldown
 * between tabs. Fully synchronous navigation removed that delay but could briefly visit every route
 * in a burst of taps. Waiting for exactly one Compose frame lets a same-frame burst collapse into
 * the newest request, while a tap on the following frame can immediately retarget an in-flight
 * NavHost transition. In practice the input latency is one frame rather than the lifetime of a
 * spring animation.
 */
internal class MainTabNavigator(
    private val navController: NavHostController,
    private val scope: CoroutineScope,
) {
    private var pendingNavigation: Job? = null

    fun select(route: String, onReselected: () -> Unit = {}) {
        pendingNavigation?.cancel()

        val originEntry = navController.currentBackStackEntry ?: return
        pendingNavigation =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withFrameNanos { }

                // A back press or unrelated navigation during this one-frame window invalidates the
                // stale request instead of reopening a destination the user has already left.
                if (navController.currentBackStackEntry !== originEntry) return@launch

                if (originEntry.destination.route == route) {
                    onReselected()
                    return@launch
                }

                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
    }
}