package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets

/** The same native drag/fling contract as the player queue; no settings-only velocity cutoff. */
@Composable
internal fun rememberSettingsFlingBehavior(): FlingBehavior = ScrollableDefaults.flingBehavior()



/**
 * Full bottom safe area (mini-player + dock/navigation + system inset) expressed as
 * scroll-content padding. Settings viewports deliberately do not consume this at the layout edge,
 * so their pixels can continue behind liquid glass while the final row still scrolls fully clear.
 */
@Composable
internal fun settingsBottomContentPadding(extra: Dp = 0.dp): Dp =
    LocalPlayerAwareWindowInsets.current
        .asPaddingValues()
        .calculateBottomPadding() + extra
