package com.nikhil.yt.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.ui.screens.Screens
import com.nikhil.yt.ui.theme.CapsuleBottomBarEnabledKey
import com.nikhil.yt.ui.theme.CapsuleThemeEnabledKey
import com.nikhil.yt.utils.rememberPreference

/**
 * Velune navigation bar with optional Capsule Dock visual layer.
 *
 * Navigation behavior and callbacks remain completely owned by Velune.
 * Capsule only replaces presentation.
 */
@Composable
fun FluidSlidingNavigationBar(
    modifier: Modifier = Modifier,
    items: List<Screens>,
    currentRoute: String,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
) {
    val capsuleThemeEnabled by rememberPreference(
        CapsuleThemeEnabledKey,
        defaultValue = false,
    )

    val capsuleBottomBarEnabled by rememberPreference(
        CapsuleBottomBarEnabledKey,
        defaultValue = false,
    )

    /*
     * For the first integration Capsule Theme also activates the dock.
     * CapsuleBottomBarEnabledKey is still kept separately for the
     * independent switch/full-immersion settings we'll connect next.
     */
    val useCapsuleDock =
        capsuleThemeEnabled || capsuleBottomBarEnabled

    if (useCapsuleDock) {
        CapsuleNavigationBar(
            modifier = modifier,
            items = items,
            currentRoute = currentRoute,
            pureBlack = pureBlack,
            onTabSelected = onTabSelected,
        )
    } else {
        OriginalVeluneNavigationBar(
            modifier = modifier,
            items = items,
            currentRoute = currentRoute,
            pureBlack = pureBlack,
            onTabSelected = onTabSelected,
        )
    }
}

/**
 * Capsule Dock adapted from the original Capsule/Metrolist implementation.
 *
 * No navigation logic is replaced.
 */
@Composable
private fun CapsuleNavigationBar(
    modifier: Modifier,
    items: List<Screens>,
    currentRoute: String,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
) {
    val selectedIndex =
        items
            .indexOfFirst {
                it.route == currentRoute
            }
            .coerceAtLeast(0)

    val dockShape =
        RoundedCornerShape(26.dp)

    val dockColor =
        if (pureBlack) {
            Color.Black
        } else {
            Color(0xFF151515)
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(dockShape)
                .background(dockColor)
                .border(
                    width = 1.dp,
                    color = Color(0xFF353535),
                    shape = dockShape,
                )
                .padding(5.dp),
    ) {
        if (items.isEmpty()) {
            return@BoxWithConstraints
        }

        val itemWidth =
            maxWidth / items.size

        val indicatorOffset by
            animateDpAsState(
                targetValue =
                    itemWidth * selectedIndex,
                animationSpec =
                    spring(
                        dampingRatio =
                            Spring.DampingRatio.NoBouncy,
                        stiffness =
                            Spring.StiffnessMediumLow,
                    ),
                label = "CapsuleDockIndicator",
            )

        /*
         * One real indicator moves between the tabs.
         * This is the same visual principle used by the old Capsule Dock.
         */
        Box(
            modifier =
                Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp)
                    .clip(
                        RoundedCornerShape(21.dp),
                    )
                    .background(
                        Color(0xFFF0F0F0),
                    ),
        )

        Row(
            modifier =
                Modifier.fillMaxSize(),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected =
                    index == selectedIndex

                val contentColor by
                    animateColorAsState(
                        targetValue =
                            if (selected) {
                                Color(0xFF101010)
                            } else {
                                Color(0xFF979797)
                            },
                        animationSpec =
                            spring(
                                dampingRatio =
                                    Spring.DampingRatio.NoBouncy,
                                stiffness =
                                    Spring.StiffnessMediumLow,
                            ),
                        label =
                            "CapsuleDockContent",
                    )

                val interactionSource =
                    remember {
                        MutableInteractionSource()
                    }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                horizontal = 2.dp,
                            )
                            .clip(
                                RoundedCornerShape(
                                    21.dp,
                                ),
                            )
                            .clickable(
                                interactionSource =
                                    interactionSource,
                                indication = null,
                            ) {
                                onTabSelected(item)
                            },
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
                                        if (selected) {
                                            item.iconIdActive
                                        } else {
                                            item.iconIdInactive
                                        },
                                ),
                            contentDescription =
                                stringResource(
                                    id = item.titleId,
                                ),
                            tint = contentColor,
                            modifier =
                                Modifier.size(22.dp),
                        )

                        Spacer(
                            modifier =
                                Modifier.height(3.dp),
                        )

                        Text(
                            text =
                                stringResource(
                                    id = item.titleId,
                                ),
                            color = contentColor,
                            fontSize = 10.sp,
                            fontWeight =
                                if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                            maxLines = 1,
                            overflow =
                                TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Original Velune implementation.
 *
 * Kept here unchanged in behavior so disabling Capsule immediately restores
 * the previous navigation appearance.
 */
@Composable
private fun OriginalVeluneNavigationBar(
    modifier: Modifier,
    items: List<Screens>,
    currentRoute: String,
    pureBlack: Boolean,
    onTabSelected: (Screens) -> Unit,
) {
    val selectedIndex =
        items
            .indexOfFirst {
                it.route == currentRoute
            }
            .coerceAtLeast(0)

    val barColor =
        if (pureBlack) {
            Color.Black
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    BoxWithConstraints(
        modifier =
            modifier
                .clip(
                    RoundedCornerShape(28.dp),
                )
                .fillMaxWidth()
                .height(80.dp)
                .background(barColor),
    ) {
        if (items.isEmpty()) {
            return@BoxWithConstraints
        }

        val tabWidth =
            maxWidth / items.size

        val pillWidth = 48.dp
        val pillHeight = 32.dp

        val indicatorOffset by
            animateDpAsState(
                targetValue =
                    (tabWidth * selectedIndex) +
                        ((tabWidth - pillWidth) / 2),
                animationSpec =
                    spring(
                        dampingRatio =
                            Spring.DampingRatio.NoBouncy,
                        stiffness =
                            Spring.StiffnessLow,
                    ),
                label = "PillSlider",
            )

        Box(
            modifier =
                Modifier
                    .offset(
                        x = indicatorOffset,
                        y = 14.dp,
                    )
                    .width(pillWidth)
                    .height(pillHeight)
                    .background(
                        color =
                            MaterialTheme.colorScheme
                                .secondaryContainer,
                        shape = CircleShape,
                    ),
        )

        Row(
            modifier =
                Modifier.fillMaxSize(),
            verticalAlignment =
                Alignment.Top,
        ) {
            items.forEachIndexed { index, item ->
                val selected =
                    selectedIndex == index

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource =
                                    remember {
                                        MutableInteractionSource()
                                    },
                                indication = null,
                            ) {
                                onTabSelected(item)
                            },
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                ) {
                    Spacer(
                        modifier =
                            Modifier.height(18.dp),
                    )

                    Icon(
                        painter =
                            painterResource(
                                id =
                                    if (selected) {
                                        item.iconIdActive
                                    } else {
                                        item.iconIdInactive
                                    },
                            ),
                        contentDescription =
                            stringResource(
                                id = item.titleId,
                            ),
                        tint =
                            if (selected) {
                                MaterialTheme.colorScheme
                                    .onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            },
                        modifier =
                            Modifier.size(24.dp),
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp),
                    )

                    Text(
                        text =
                            stringResource(
                                id = item.titleId,
                            ),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow =
                            TextOverflow.Ellipsis,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme
                                    .onSurface
                            } else {
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}
