package com.nikhil.yt.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberMainTabNavigator(navController: NavHostController): MainTabNavigator {
    val scope = rememberCoroutineScope()
    val navigator = remember(navController, scope) { MainTabNavigator(navController, scope) }

    DisposableEffect(navigator) {
        navigator.attach()
        onDispose { navigator.detach() }
    }

    return navigator
}

/**
 * Top-level tabs coalesce only taps that land before the next UI frame. Capsule deliberately has no
 * route-level transition: the destination canvas swaps directly and destination-owned controls do
 * the visible scene motion. ComposeNavigator still tracks navigation as a transition even when the
 * NavHost returns Enter/ExitTransition.None, so we settle that invisible bookkeeping ourselves on
 * the following frame. Entries are captured synchronously on every destination change so an
 * immediate Back cannot lose the just-popped destination before it is marked complete.
 *
 * MainTabNavigator can enter composition before NavHost has attached ComposeNavigator to the
 * NavController. Navigator.state deliberately throws before that attachment, so every state access
 * is guarded by ComposeNavigator.isAttached. This is especially important during a cold start where
 * DisposableEffect ordering can differ from the steady-state navigation path.
 */
internal class MainTabNavigator(
    private val navController: NavHostController,
    private val scope: CoroutineScope,
) {
    private var pendingNavigation: Job? = null
    private var transitionCompletion: Job? = null
    private var attached = false
    private var knownComposeEntries: Set<NavBackStackEntry> = emptySet()

    private val destinationListener =
        NavController.OnDestinationChangedListener { _, _, _ ->
            captureComposeEntries()
            scheduleTransitionCompletion()
        }

    fun attach() {
        if (attached) return
        attached = true
        captureComposeEntries()
        navController.addOnDestinationChangedListener(destinationListener)
        scheduleTransitionCompletion()
    }

    fun detach() {
        if (!attached) return
        attached = false
        navController.removeOnDestinationChangedListener(destinationListener)
        pendingNavigation?.cancel()
        transitionCompletion?.cancel()
        pendingNavigation = null
        transitionCompletion = null
        knownComposeEntries = emptySet()
    }

    fun select(route: String, onReselected: () -> Unit = {}) {
        pendingNavigation?.cancel()

        val originEntry = navController.currentBackStackEntry ?: return
        pendingNavigation =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withFrameNanos { }

                // Back or unrelated navigation during this one-frame coalescing window invalidates
                // the stale tab request instead of reopening a destination the user has already left.
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

    private fun captureComposeEntries() {
        if (!attached) return
        val composeNavigator = composeNavigator()
        if (!composeNavigator.isAttached) return

        val currentEntries = composeNavigator.backStack.value.toSet()
        knownComposeEntries = knownComposeEntries + currentEntries
    }

    private fun scheduleTransitionCompletion() {
        if (!attached) return
        transitionCompletion?.cancel()
        transitionCompletion =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Let NavHost consume the destination change first, then settle the otherwise
                // invisible ComposeNavigator transition. The captured set already includes the
                // outgoing entry, even when a Back press removes it before this frame arrives.
                withFrameNanos { }
                completeComposeTransitions()
            }
    }

    private fun completeComposeTransitions() {
        if (!attached) return

        val composeNavigator = composeNavigator()
        if (!composeNavigator.isAttached) return

        val currentEntries = composeNavigator.backStack.value.toSet()
        val entriesToComplete = knownComposeEntries + currentEntries

        entriesToComplete.forEach { entry ->
            runCatching { composeNavigator.onTransitionComplete(entry) }
        }

        knownComposeEntries = currentEntries
    }

    private fun composeNavigator(): ComposeNavigator =
        navController.navigatorProvider.getNavigator(ComposeNavigator::class.java)
}
