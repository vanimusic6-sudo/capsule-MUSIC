package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Consistent inertial scrolling across all Capsule settings pages.
 * A moderate velocity cap keeps long lists from suddenly flying to the bottom,
 * without changing drag distance or overscroll behavior.
 */
@Composable
internal fun rememberSettingsFlingBehavior(): FlingBehavior {
    val defaultFling = ScrollableDefaults.flingBehavior()
    val density = LocalDensity.current
    return remember(defaultFling, density) {
        val maxVelocity = with(density) { 2400.dp.toPx() }
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(defaultFling) {
                    this@performFling.performFling(
                        initialVelocity.coerceIn(-maxVelocity, maxVelocity),
                    )
                }
        }
    }
}
