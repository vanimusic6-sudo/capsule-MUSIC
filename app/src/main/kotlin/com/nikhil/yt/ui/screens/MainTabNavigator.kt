package com.nikhil.yt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun rememberMainTabNavigator(navController: NavHostController): MainTabNavigator {
    val scope = rememberCoroutineScope()
    return remember(navController, scope) { MainTabNavigator(navController, scope) }
}

/** Finish the current transition, then open only the last tab the user requested. */
internal class MainTabNavigator(
    private val navController: NavHostController,
    private val scope: CoroutineScope,
) {
    private var pendingNavigation: Job? = null

    fun select(route: String, onReselected: () -> Unit = {}) {
        pendingNavigation?.cancel()
        pendingNavigation = scope.launch {
            val entry = navController.currentBackStackEntry ?: return@launch
            // NavHost resumes its entry when the animation finishes. A back press or
            // another navigation invalidates this request instead of opening a stale tab.
            combine(navController.currentBackStackEntryFlow, entry.lifecycle.currentStateFlow) { current, state ->
                current !== entry || state == Lifecycle.State.RESUMED || state == Lifecycle.State.DESTROYED
            }.first { it }
            if (navController.currentBackStackEntry !== entry ||
                entry.lifecycle.currentState != Lifecycle.State.RESUMED
            ) return@launch

            if (entry.destination.route == route) {
                onReselected()
            } else {
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
}
