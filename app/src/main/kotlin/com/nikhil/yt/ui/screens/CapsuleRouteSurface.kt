package com.nikhil.yt.ui.screens

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nikhil.yt.R
import com.nikhil.yt.constants.AppBarHeight
import com.nikhil.yt.ui.component.StandardHeaderTitle
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import com.nikhil.yt.ui.motion.CapsuleStandardEasing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    // Recorded once per composition of this destination, which is once per arrival: with route
    // transitions at None a destination is disposed as soon as it is left, so a screen that comes
    // back has genuinely been re-entered.
    val arrival = remember(entry) { NavigationHistory.enter(route) }
    // The motion depends on where the destination was reached from, not only on what it is: opening
    // settings and stepping back to its root are different events landing on the same route.
    val motion = remember(arrival) { destinationMotionFor(route, arrival.from) }
    // Settings alone uses real NavHost spatial transitions: its outgoing
    // canvas must uncover the previously composed Home/settings page. Never
    // animate that canvas AGAIN inside its own route wrapper, or the two
    // independent transforms will hitch and the Home page will slide too.
    val settingsNavigation =
        route == "settings" || route.startsWith("settings/") ||
            arrival.from == "settings" || arrival.from?.startsWith("settings/") == true
    val showScrollAwayHeader = route in setOf("home", "library", "history", "stats")
    CompositionLocalProvider(LocalNavBackStackEntry provides entry) {
        CapsuleRouteSurface {
            if (showScrollAwayHeader) {
                // Main pages own their chrome and scroll it away. Settings and
                // other detail destinations do not create any header observer,
                // spring or second layer.
                RouteWithScrollAwayHeader(entry) {
                    Box(
                        modifier = if (settingsNavigation) Modifier else
                            Modifier.destinationEntrance(motion, arrival.direction),
                    ) {
                        with(contentScope) { content(entry) }
                    }
                }
            } else {
                Box(
                    modifier = if (settingsNavigation) Modifier else
                        Modifier.destinationEntrance(motion, arrival.direction),
                ) {
                    with(contentScope) { content(entry) }
                }
            }
        }
    }
}

/**
 * The single history shared by every destination in the graph.
 *
 * There is one NavHost in the app, and destinations are siblings with no composition between them to
 * hold shared state, so this is where "the route before this one" can live. Composition runs on the
 * main thread, so nothing here needs synchronising.
 *
 * Being process-wide has one consequence worth naming: it outlives the NavHost that filled it. A
 * history left over from a previous graph can only ever cost a single entrance the wrong direction,
 * after which it is correct again — but it also means a test that never navigates still inherits
 * whatever the last one did, which is why [resetNavigationHistory] exists.
 */
private val NavigationHistory = RouteHistory()

/**
 * Forgets which route was composed last.
 *
 * Call this when a new navigation graph starts, so its first destination is treated as a beginning
 * rather than as a step away from whatever the previous graph ended on.
 */
internal fun resetNavigationHistory() = NavigationHistory.clear()

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

/** Account and navigation come from the Activity, but the header itself belongs
 * to its NavHost destination so it stays visually attached during transitions. */
val LocalCapsuleHeaderAccountName = compositionLocalOf<String?> { null }
val LocalCapsuleHeaderAccountImage = compositionLocalOf<String?> { null }
val LocalCapsuleHeaderNavController = compositionLocalOf<NavHostController?> { null }

/**
 * Independent viewport chrome owned by one NavBackStackEntry.
 *
 * It is NEVER an item in a LazyColumn and NEVER a fixed Activity topBar.
 * On scrolling down the first bar-height worth of consumed pixels shifts
 * the header away; scrolling up in the middle of the list does not make it
 * reappear. Only reaching the real top (unconsumed upwards gesture) reveals
 * the whole header. A single short tween smooths that final return.
 *
 * Animated state is read in Modifier.offset's placement lambda. The NavHost
 * destination and its whole LazyColumn do not have to recompose for each
 * animation frame. When the header is fully hidden, no updates occur.
 */
@Composable
private fun RouteWithScrollAwayHeader(
    entry: NavBackStackEntry,
    content: @Composable () -> Unit,
) {
    val headerHeight = AppBarHeight + with(LocalDensity.current) {
        WindowInsets.systemBars.getTop(this).toDp()
    }
    val hiddenPx = with(LocalDensity.current) { headerHeight.toPx() }
    var headerOffsetPx by remember(entry) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val revealJob = remember(entry) { arrayOfNulls<Job>(1) }
    // Recording 9496: 'available.y > 0' alone fired on tiny overscroll
    // rebounds and system flings, making the header pop back mid-scroll,
    // immediately disappear, and sometimes overlay the feed.
    //
    // Require meaningful upward overscroll while the list is ACTUALLY
    // at top: 12dp for finger drag or 36dp leftover from a top-reaching
    // fling. Ignore sub-pixel recoil. No new animation
    // controllers are allocated per gesture or per frame.
    val pullThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }
    val hideNoiseFloorPx = with(LocalDensity.current) { 1.5.dp.toPx() }
    val returnEasing = CapsuleStandardEasing
    val scrollAwayConnection = remember(entry, hiddenPx, scope, pullThresholdPx, hideNoiseFloorPx) {
        object : NestedScrollConnection {
            private var positiveOverscroll = 0f

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) {
                    // A fling reaching the top should reveal the greeting
                    // automatically, not require another intentional pull.
                    // Tiny overscroll spring-back isn't a genuine arrival:
                    // require a sufficiently large positive leftover.
                    if (consumed.y >= 0f &&
                        available.y >= pullThresholdPx * 3f &&
                        headerOffsetPx < 0f &&
                        revealJob[0]?.isActive != true
                    ) {
                        revealJob[0] = scope.launch {
                            animate(
                                initialValue = headerOffsetPx,
                                targetValue = 0f,
                                animationSpec = tween(240, easing = returnEasing),
                            ) { value, _ -> headerOffsetPx = value }
                        }
                    } else if (consumed.y < -hideNoiseFloorPx &&
                        headerOffsetPx > -hiddenPx &&
                        revealJob[0]?.isActive != true
                    ) {
                        // After return begins ignore inertial rebound. Only a
                        // *new finger drag* may interrupt the visible header.
                        headerOffsetPx = (headerOffsetPx + consumed.y).coerceIn(-hiddenPx, 0f)
                    }
                    return Offset.Zero
                }
                when {
                    consumed.y < -hideNoiseFloorPx -> {
                        positiveOverscroll = 0f
                        revealJob[0]?.cancel()
                        revealJob[0] = null
                        headerOffsetPx = (headerOffsetPx + consumed.y)
                            .coerceIn(-hiddenPx, 0f)
                    }
                    // A list consuming upward travel means we're in the
                    // middle of content. Keep the header tucked away.
                    consumed.y > 0f -> positiveOverscroll = 0f
                    available.y > 0f && headerOffsetPx < 0f -> {
                        positiveOverscroll =
                            (positiveOverscroll + available.y).coerceAtMost(pullThresholdPx * 2f)
                        if (positiveOverscroll >= pullThresholdPx &&
                            revealJob[0]?.isActive != true
                        ) {
                            positiveOverscroll = 0f
                            revealJob[0] = scope.launch {
                                animate(
                                    initialValue = headerOffsetPx,
                                    targetValue = 0f,
                                    animationSpec = tween(240, easing = returnEasing),
                                ) { value, _ ->
                                    headerOffsetPx = value
                                }
                            }
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollAwayConnection),
    ) {
        content()
        val navController = LocalCapsuleHeaderNavController.current
        CapsuleStandaloneHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, headerOffsetPx.toInt()) },
            onAccountClick = { navController?.navigate("settings/account") },
            onSettingsClick = { navController?.navigate("settings") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapsuleStandaloneHeader(
    modifier: Modifier,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        title = {
            StandardHeaderTitle(
                accountName = LocalCapsuleHeaderAccountName.current,
                accountImageUrl = LocalCapsuleHeaderAccountImage.current,
                onAccountClick = onAccountClick,
            )
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = stringResource(R.string.settings),
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            // Opaque header surface prevents list artwork/text showing
            // through the greeting while the chrome slides out.
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}


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
