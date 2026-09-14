package com.nikhil.yt.ui.screens

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Registers a destination that owns an opaque full-screen canvas.
 *
 * Navigation Compose keeps the outgoing and incoming destinations composed at the same time while it
 * settles lifecycle state. Screens that paint no background of their own therefore show through each
 * other, and a Back press is where it is easiest to catch. No arrangement of transition timings
 * fixes that — the fix is for a destination to be opaque.
 *
 * The wrapper carries no animation, no alpha and no `graphicsLayer`. That is deliberate: an earlier
 * attempt put the route's entrance fade on this same layer, which made every destination
 * translucent for the length of the entrance and, with the layer's constraints, left screens
 * unscrollable and blank. A destination's canvas has exactly one job.
 */
internal fun NavGraphBuilder.routeComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments, deepLinks) { entry ->
    val contentScope = this
    CompositionLocalProvider(LocalNavBackStackEntry provides entry) {
        CapsuleRouteSurface { with(contentScope) { content(entry) } }
    }
}

/**
 * The entry that owns the destination currently being composed.
 *
 * Screens must read this instead of `navController.currentBackStackEntryAsState()`. That reports
 * whichever entry is globally current, which has two consequences, and both of them are bugs:
 *
 * - every screen observing it recomposes on *any* navigation, including the screen that is being
 *   navigated away from and torn down. That recomposition re-resolves the screen's ViewModels from
 *   an entry that may already be destroyed, which throws
 *   `IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the
 *   NavBackStackEntry is destroyed` — the crash reported from a heavy tab-switching session;
 * - it is simply the wrong entry. A screen reading saved state off whatever destination happens to
 *   be current is reading someone else's state.
 *
 * This value is the screen's own entry, so its lifetime matches the composition that reads it: it
 * cannot be destroyed while that composition is alive, and it never changes underneath the screen.
 */
val LocalNavBackStackEntry = compositionLocalOf<NavBackStackEntry?> { null }

@Composable
internal fun CapsuleRouteSurface(content: @Composable () -> Unit) {
    /*
     * A plain background layer, not a Surface.
     *
     * Surface would also take over LocalContentColor, which the activity sets deliberately — pure
     * black mode forces white content that `contentColorFor(surface)` would undo — and it adds
     * semantics and an empty pointerInput that this layer has no business introducing. The colour
     * is the surface the activity paints its own root with, so a destination and the window behind
     * it can never disagree.
     *
     * Minimum constraints are deliberately not propagated. NavHost hands its content a zero
     * minimum and aligns it top-start, so a screen sees exactly the constraints and the position it
     * saw before this wrapper existed; the only difference is that there is now an opaque colour
     * behind it. Forcing a minimum size instead would silently stretch any screen that does not
     * fill the window, and this wrapper has no business changing layout at all.
     */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}
