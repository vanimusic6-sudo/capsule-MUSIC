package com.nikhil.yt.ui.component

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.constants.MiniPlayerBackgroundStyleKey
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.player.CapsuleCompactSurfaceBackground
import com.nikhil.yt.ui.player.capsuleDockIndicatorColor
import com.nikhil.yt.ui.player.capsuleSurfaceOutline
import com.nikhil.yt.ui.player.rememberCapsuleArtworkColors
import com.nikhil.yt.ui.theme.CapsuleBottomBarEnabledKey
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

@Composable
fun FluidSlidingNavigationBar(
    modifier: Modifier = Modifier,
    items: List<Screens>,
    currentRoute: String,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
    capsuleMiniPlayerVisible: Boolean = false,
) {
    val capsuleBottomBarEnabled by
        rememberPreference(
            CapsuleBottomBarEnabledKey,
            defaultValue = false,
        )

    if (capsuleBottomBarEnabled) {
        CapsuleNavigationBar(
            modifier = modifier,
            items = items,
            currentRoute = currentRoute,
            pureBlack = pureBlack,
            onTabSelected = onTabSelected,
            connectedToMiniPlayer =
                capsuleMiniPlayerVisible,
        )
    } else {
        StandardNavigationBar(
            modifier = modifier,
            items = items,
            currentRoute = currentRoute,
            onTabSelected = onTabSelected,
        )
    }
}

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
        "search_input" &&
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

/**
 * Original Capsule Dock port.
 *
 * The important detail is connectedToMiniPlayer:
 * top radius becomes 0 dp while the Capsule Mini Player is directly above.
 */
@Composable
private fun CapsuleNavigationBar(
    modifier: Modifier,
    items: List<Screens>,
    currentRoute: String?,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
    connectedToMiniPlayer: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata =
        if (playerConnection == null) {
            null
        } else {
            val value by
                playerConnection.mediaMetadata.collectAsState()
            value
        }
    val miniPlayerBackground by
        rememberEnumPreference(
            MiniPlayerBackgroundStyleKey,
            defaultValue = MiniPlayerBackgroundStyle.CAPSULE_STAR,
        )
    val dockBackground =
        if (connectedToMiniPlayer) {
            miniPlayerBackground
        } else {
            MiniPlayerBackgroundStyle.THEME
        }
    val artworkColors =
        rememberCapsuleArtworkColors(
            mediaMetadata = mediaMetadata,
            enabled =
                connectedToMiniPlayer &&
                    dockBackground !=
                    MiniPlayerBackgroundStyle.THEME,
        )
    val glassDock = dockBackground == MiniPlayerBackgroundStyle.GLASS
    val dockOutline = capsuleSurfaceOutline(artworkColors, glass = glassDock)
    val dockIndicator = capsuleDockIndicatorColor(artworkColors, glass = glassDock)
    val dockMutedContent =
        lerp(
            Color(0xFFA2A1AA),
            artworkColors.firstOrNull() ?: Color(0xFFA2A1AA),
            0.08f,
        )

    val dockTopRadius by
        animateDpAsState(
            targetValue =
                if (connectedToMiniPlayer) {
                    0.dp
                } else {
                    26.dp
                },
            animationSpec =
                spring(
                    dampingRatio =
                        Spring.DampingRatioNoBouncy,
                    stiffness =
                        Spring.StiffnessMediumLow,
                ),
            label =
                "capsuleDockTopRadius",
        )

    val dockShape =
        RoundedCornerShape(
            topStart =
                dockTopRadius,
            topEnd =
                dockTopRadius,
            bottomStart =
                26.dp,
            bottomEnd =
                26.dp,
        )

    val selectedIndex =
        remember(
            currentRoute,
            items,
        ) {
            items
                .indexOfFirst { screen ->
                    isRouteSelected(
                        currentRoute,
                        screen.route,
                        items,
                    )
                }
                .coerceAtLeast(0)
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(
                        WindowInsetsSides.Bottom,
                    ),
                )
                .padding(horizontal = 10.dp)
                .padding(bottom = 6.dp),
        contentAlignment =
            Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(dockShape)
                    .border(
                        width = 1.dp,
                        color = dockOutline,
                        shape =
                            dockShape,
                    ),
        ) {
            CapsuleCompactSurfaceBackground(
                style = dockBackground,
                pureBlack = pureBlack,
                colors = artworkColors,
                modifier = Modifier.fillMaxSize(),
                // The mini-player carries the motion. A matching static phase
                // keeps the connected dock cohesive without a second
                // full-time animation clock and Canvas redraw loop.
                animated = false,
            )

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(5.dp),
            ) {
                if (items.isEmpty()) {
                    return@BoxWithConstraints
                }

                val itemCount =
                    items.size.coerceAtLeast(1)

                val itemWidth =
                    maxWidth /
                        itemCount.toFloat()

                val indicatorOffset by
                    animateDpAsState(
                        targetValue =
                            itemWidth *
                                selectedIndex
                                    .toFloat(),
                        animationSpec =
                            spring(
                                dampingRatio =
                                    Spring.DampingRatioNoBouncy,
                                stiffness =
                                    Spring.StiffnessMediumLow,
                            ),
                        label =
                            "capsuleDockIndicatorOffset",
                    )

                Box(
                    modifier =
                        Modifier
                            .offset(
                                x =
                                    indicatorOffset,
                            )
                            .width(itemWidth)
                            .fillMaxHeight()
                            .padding(
                                horizontal = 2.dp,
                            )
                            .clip(
                                RoundedCornerShape(
                                    21.dp,
                                ),
                            )
                            .background(
                                dockIndicator,
                            ),
                )

                Row(
                    modifier =
                        Modifier.fillMaxSize(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    items.forEach { screen ->
                    val isSelected =
                        remember(
                            currentRoute,
                            screen.route,
                        ) {
                            isRouteSelected(
                                currentRoute,
                                screen.route,
                                items,
                            )
                        }

                    val iconRes =
                        remember(
                            isSelected,
                            screen,
                        ) {
                            if (isSelected) {
                                screen.iconIdActive
                            } else {
                                screen.iconIdInactive
                            }
                        }

                    val interactionSource =
                        remember {
                            MutableInteractionSource()
                        }

                    val itemContent by
                        animateColorAsState(
                            targetValue =
                                if (isSelected) {
                                    Color(0xFF121219)
                                } else {
                                    dockMutedContent
                                },
                            animationSpec =
                                spring(
                                    dampingRatio =
                                        Spring.DampingRatioNoBouncy,
                                    stiffness =
                                        Spring.StiffnessMediumLow,
                                ),
                            label =
                                "capsuleDockItemColor",
                        )

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(
                                    horizontal =
                                        2.dp,
                                )
                                .clip(
                                    RoundedCornerShape(
                                        21.dp,
                                    ),
                                )
                                .clickable(
                                    interactionSource =
                                        interactionSource,
                                    indication =
                                        LocalIndication.current,
                                    onClick = {
                                        onTabSelected(
                                            screen,
                                        )
                                    },
                                ),
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        id =
                                            iconRes,
                                    ),
                                contentDescription =
                                    stringResource(
                                        screen.titleId,
                                    ),
                                tint =
                                    itemContent,
                                modifier =
                                    Modifier.size(
                                        21.dp,
                                    ),
                            )

                            Text(
                                text =
                                    stringResource(
                                        screen.titleId,
                                    ),
                                color =
                                    itemContent,
                                fontSize =
                                    10.sp,
                                fontWeight =
                                    if (isSelected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Medium
                                    },
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.padding(
                                        top = 2.dp,
                                    ),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Standard full-width navigation, with the system inset inside its surface. */
@Composable
internal fun StandardNavigationBar(
    modifier: Modifier,
    items: List<Screens>,
    currentRoute: String,
    onTabSelected: (Screens) -> Unit,
) {
    val selectedIndex = items.indexOfFirst { isRouteSelected(currentRoute, it.route, items) }.coerceAtLeast(0)
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
