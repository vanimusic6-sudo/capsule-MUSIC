package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.gestures.FlingBehavior
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets

/** Compatibility adapter: settings now use the same friction as all other lists. */
@Composable
internal fun rememberSettingsFlingBehavior(): FlingBehavior = rememberCapsuleFlingBehavior()


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
