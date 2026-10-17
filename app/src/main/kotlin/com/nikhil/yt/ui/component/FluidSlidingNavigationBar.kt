package com.nikhil.yt.ui.component

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.ui.screens.Screens

@Composable
fun FluidSlidingNavigationBar(
    modifier: Modifier = Modifier,
    items: List<Screens>,
    currentRoute: String,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
    capsuleMiniPlayerVisible: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    StandardNavigationBar(
        modifier = modifier,
        items = items,
        currentRoute = currentRoute,
        onTabSelected = onTabSelected,
        interactionEnabled = interactionEnabled,
    )
}

/**
 * Which tab the highlight sits on, including on screens that are not tabs at all.
 *
 * The bar is now on screen everywhere, and an artist, a playlist or a settings page matches no tab.
 * Both call sites used to end in `.coerceAtLeast(0)`, which turns "no match" into index 0 — so
 * opening an artist from the library would have slid the highlight across to Home and opening
 * anything from Home would have looked correct by luck. That was invisible while the bar hid itself
 * on those screens; it is not invisible any more.
 *
 * Holding the last tab that did match is both the honest answer and the useful one: the screen you
 * opened came from somewhere, and the bar keeps saying where.
 */
@Composable
internal fun selectedTabIndex(
    currentRoute: String?,
    items: List<Screens>,
): Int {
    val lastMatched = remember(items) { LastSelectedTab() }
    val matched = items.indexOfFirst { isRouteSelected(currentRoute, it.route, items) }
    if (matched >= 0) lastMatched.index = matched
    return lastMatched.index
}

/**
 * Deliberately a plain object rather than snapshot state.
 *
 * It is written during composition, and a snapshot write read back in the same pass schedules
 * another composition for no reason. Nothing needs to observe this: the route it is derived from is
 * already observed upstream, so a change of route recomposes this bar anyway.
 */
private class LastSelectedTab(var index: Int = 0)

private fun isRouteSelected(
    currentRoute: String?,
    screenRoute: String,
    navigationItems: List<Screens>,
): Boolean {
    if (currentRoute == null) {
        return false
    }

    if (currentRoute == screenRoute) {
        return true
    }

    if (
        navigationItems.any {
            it.route == screenRoute
        } &&
        currentRoute.startsWith(
            "$screenRoute/",
        )
    ) {
        return true
    }

    if (
        screenRoute ==
        Screens.Search.route &&
        (
            currentRoute.startsWith(
                "search/",
            ) ||
                currentRoute ==
                "search/{query}"
        )
    ) {
        return true
    }

    return false
}

/** Standard full-width navigation, with the system inset inside its surface. */
@Composable
internal fun StandardNavigationBar(
    modifier: Modifier,
    items: List<Screens>,
    currentRoute: String,
    onTabSelected: (Screens) -> Unit,
    interactionEnabled: Boolean,
) {
    val selectedIndex = selectedTabIndex(currentRoute, items)
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(StandardChrome.panel)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        if (items.isEmpty()) return@BoxWithConstraints
        val compact = maxHeight < 72.dp
        val tabWidth = maxWidth / items.size
        val pillWidth = 54.dp
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex + (tabWidth - pillWidth) / 2,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
            label = "standardNavigationIndicator",
        )
        Box(
            Modifier.offset(x = indicatorOffset, y = if (compact) 8.dp else 12.dp).size(pillWidth, if (compact) 32.dp else 36.dp)
                .background(StandardChrome.selected, CircleShape),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .selectable(
                            selected = selected,
                            enabled = interactionEnabled,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(item) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(if (compact) 12.dp else 17.dp))
                    Icon(
                        painterResource(if (selected) item.iconIdActive else item.iconIdInactive),
                        contentDescription = null,
                        tint = StandardChrome.muted,
                        modifier = Modifier.size(if (compact) 24.dp else 26.dp),
                    )
                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                    Text(
                        stringResource(item.titleId),
                        fontSize = if (compact) 12.sp else 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) StandardChrome.text else StandardChrome.muted,
                    )
                }
            }
        }
    }
}
