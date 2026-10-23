package com.nikhil.yt.ui.screens

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nikhil.yt.R
import com.nikhil.yt.constants.AppBarHeight
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.ui.component.StandardHeaderTitle
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.constants.HomeFeedSourcesKey
import com.nikhil.yt.constants.ExperimentalSourcesEnabledKey
import com.nikhil.yt.discovery.HomeFeedSource
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.utils.PreferenceStore
import com.nikhil.yt.utils.dataStore

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
    // Settings animates the *whole destination*. A pinned Activity Scaffold
    // topBar lived outside NavHost: it could pop in/out while the returning
    // Home/Library canvas was still halfway through its ArchiveTune transition.
    //
    // Own the fixed (non-scrollable) bar INSIDE the main destination instead.
    // No scroll observer or own animation: NavHost's EXISTING slide/fade
    // carries both the page and its chrome together during Settings Back.
    val mainDestination = route in setOf("home", "library", "history", "stats")

    // Do not derive a departing main page's top padding from the global
    // currentRoute: while Settings enters, currentRoute already says
    // "settings", even though the old Home is still on screen animating.
    // Preserve the main destination's status-bar + fixed chrome top
    // reservation; delegate bottom/horizontal to the live app shell.
    val parentInsets = LocalPlayerAwareWindowInsets.current
    val destinationInsets = if (mainDestination) {
        parentInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .add(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .add(WindowInsets(top = AppBarHeight))
    } else parentInsets

    CompositionLocalProvider(
        LocalNavBackStackEntry provides entry,
        LocalPlayerAwareWindowInsets provides destinationInsets,
    ) {
        CapsuleRouteSurface {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (settingsNavigation) Modifier else
                        Modifier.destinationEntrance(motion, arrival.direction),
                ) {
                    with(contentScope) { content(entry) }
                }
                if (mainDestination && LocalCapsulePinnedHeaderVisible.current) {
                    CapsulePinnedHeader(route = route)
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
val LocalCapsulePinnedHeaderVisible = compositionLocalOf { true }

/**
 * Pinned (scroll-independent) header that belongs to the same NavHost
 * destination as the main feed. No separate scale, fade or scroll physics.
 *
 * Colors follow the *screen* instead of blindly using Material surface:
 * Home paints StandardChrome.background + the 430dp tonal wash; Library,
 * History and Stats paint MaterialTheme.surface as their foundation.
 * Replicating only the Home wash's first few pixels costs one tiny
 * drawWithCache rect and avoids a visible solid-color strip above the feed.
 */
@Composable
fun CapsulePinnedHeader(route: String) {
    val navController = LocalCapsuleHeaderNavController.current
    val isHome = route == "home"
    val base = if (isHome) StandardChrome.background else MaterialTheme.colorScheme.surface
    // Home and its pinned header must paint exactly the same opaque surface.
    // The earlier header-only tonal wash produced a visible horizontal seam.

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(base)
            .statusBarsPadding()
            .height(AppBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (isHome) {
                val context = LocalContext.current
                val (raw, setRaw) = rememberPreference(
                    HomeFeedSourcesKey, HomeFeedSource.DEFAULT,
                )
                val (experimental, setExperimental) = rememberPreference(
                    ExperimentalSourcesEnabledKey, false,
                )
                var chooserOpen by remember { mutableStateOf(false) }
                val selected = HomeFeedSource.parse(raw)
                val effective = if (experimental) selected
                    else setOf(HomeFeedSource.YOUTUBE)
                val sourceIcon = if (effective.size == 1) effective.first().icon
                    else R.drawable.ic_capsule_home_sources
                StandardHeaderTitle(
                    accountName = LocalCapsuleHeaderAccountName.current,
                    accountImageUrl = LocalCapsuleHeaderAccountImage.current,
                    onAccountClick = { chooserOpen = true },
                    sourceIcon = sourceIcon,
                    sourceAccents = HomeFeedSource.selectable.filter { it in effective }
                        .map { it.accent },
                    sourceIcons = HomeFeedSource.selectable.filter { it in effective }
                        .map { it.icon },
                    sourceContentDescription = stringResource(R.string.capsule_home_source_pick),
                )
                DropdownMenu(
                    expanded = chooserOpen,
                    onDismissRequest = { chooserOpen = false },
                    modifier = Modifier.clip(RoundedCornerShape(24.dp))
                        .animateContentSize(spring(dampingRatio = 0.86f, stiffness = 480f)),
                    containerColor = StandardChrome.panel,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                ) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 7.dp),
                    ) {
                        Text(stringResource(R.string.capsule_home_source_pick),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.capsule_source_selector_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = StandardChrome.muted)
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.capsule_home_all_sources)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_capsule_home_sources),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            // Select all providers atomically: separate asynchronous
                            // writes raced the experimental gate on first opt-in.
                            PreferenceStore.launchEdit(context.dataStore) {
                                this[HomeFeedSourcesKey] =
                                    HomeFeedSource.encode(HomeFeedSource.selectable.toSet())
                                this[ExperimentalSourcesEnabledKey] = true
                            }
                            chooserOpen = false
                        },
                    )
                    // Reset only Home's selected providers. Leave the user's
                    // optional catalog/search preference unchanged.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.capsule_home_reset_youtube)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.restore),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            setRaw(HomeFeedSource.DEFAULT)
                            chooserOpen = false
                        },
                    )
                    HomeFeedSource.selectable.forEach { source ->
                        // Show what actually feeds Home. Experimental catalogs are
                        // inactive until opted in, regardless of stored defaults.
                        val active = source in effective
                        DropdownMenuItem(
                            text = { Text(source.label) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(30.dp).background(
                                        source.accent, androidx.compose.foundation.shape.CircleShape,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(source.icon),
                                        contentDescription = null,
                                        tint = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            },
                            trailingIcon = {
                                if (active) Icon(
                                    painter = painterResource(R.drawable.done),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                val next = effective.toMutableSet()
                                // A first-time Audius/Openverse choice enables ONLY
                                // that source alongside YouTube; do not silently start
                                // every default-selected remote catalog.
                                if (source != HomeFeedSource.YOUTUBE && !experimental) {
                                    next.add(source)
                                } else {
                                    if (active && next.size > 1) next.remove(source)
                                    else next.add(source)
                                }
                                PreferenceStore.launchEdit(context.dataStore) {
                                    this[HomeFeedSourcesKey] = HomeFeedSource.encode(next)
                                    if (source != HomeFeedSource.YOUTUBE) {
                                        this[ExperimentalSourcesEnabledKey] = true
                                    }
                                }
                                chooserOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.person),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            chooserOpen = false
                            navController?.navigate("settings/account")
                        },
                    )
                }
            } else {
                StandardHeaderTitle(
                    accountName = LocalCapsuleHeaderAccountName.current,
                    accountImageUrl = LocalCapsuleHeaderAccountImage.current,
                    onAccountClick = { navController?.navigate("settings/account") },
                )
            }
        }
        IconButton(onClick = { navController?.navigate("settings") }) {
            Icon(
                painter = painterResource(R.drawable.settings),
                contentDescription = stringResource(R.string.settings),
                modifier = Modifier.size(24.dp),
            )
        }
    }
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
