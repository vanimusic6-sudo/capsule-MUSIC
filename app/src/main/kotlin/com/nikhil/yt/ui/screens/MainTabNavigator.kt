package com.nikhil.yt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController

@Composable
internal fun rememberMainTabNavigator(navController: NavHostController): MainTabNavigator =
    remember(navController) { MainTabNavigator(navController) }

/**
 * Switch top-level tabs immediately.
 *
 * NavHost can retarget an in-flight transition. Waiting for the current back-stack entry to become
 * RESUMED made quick taps feel queued: History -> Library, for example, could not react until the
 * previous animation had completely settled. The newest tap now becomes the target immediately.
 */
internal class MainTabNavigator(
    private val navController: NavHostController,
) {
    fun select(route: String, onReselected: () -> Unit = {}) {
        val entry = navController.currentBackStackEntry ?: return

        if (entry.destination.route == route) {
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
