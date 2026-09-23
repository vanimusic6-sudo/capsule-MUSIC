/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.utils

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
    snapAnimationSpec: AnimationSpec<Float>? = spring(stiffness = Spring.StiffnessMediumLow),
    flingAnimationSpec: DecayAnimationSpec<Float>? = rememberSplineBasedDecay(),
): TopAppBarScrollBehavior =
    AppBarScrollBehavior(
        state = state,
        snapAnimationSpec = snapAnimationSpec,
        flingAnimationSpec = flingAnimationSpec,
        canScroll = canScroll,
    )

@ExperimentalMaterial3Api
class AppBarScrollBehavior(
    override val state: TopAppBarState,
    override val snapAnimationSpec: AnimationSpec<Float>?,
    override val flingAnimationSpec: DecayAnimationSpec<Float>?,
    val canScroll: () -> Boolean = { true },
) : TopAppBarScrollBehavior {
    override val isPinned: Boolean = true
    override var nestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!canScroll()) return Offset.Zero
                state.contentOffset += consumed.y
                if (state.heightOffset == 0f || state.heightOffset == state.heightOffsetLimit) {
                    if (consumed.y == 0f && available.y > 0f) {
                        // Reset the total content offset to zero when scrolling all the way down.
                        // This will eliminate some float precision inaccuracies.
                        state.contentOffset = 0f
                    }
                }
                state.heightOffset += consumed.y
                return Offset.Zero
            }
        }
}

/**
 * Brings the app bar back to its full height.
 *
 * [animated] is not a detail. The bar is drawn at `state.heightOffset`, so animating this back to
 * zero slides the bar down the screen — which is right when the bar returns on its own (leaving
 * search, a scroll-to-top), and wrong when it happens because the user changed destination. In that
 * second case the slide arrives on top of the destination's own entrance, and the two read as one
 * movement in opposite directions: Home, which is excluded from the reset, rises into place, while
 * Library, History and Stats appeared to come down from above. That was the bar, not the screen.
 *
 * Snapping instead means the bar is simply already where it belongs by the first frame of the new
 * destination, and every tab arrives the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
suspend fun TopAppBarState.resetHeightOffset(animated: Boolean = true) {
    if (heightOffset == 0f) return
    if (!animated) {
        heightOffset = 0f
        return
    }
    animate(
        initialValue = heightOffset,
        targetValue = 0f,
    ) { value, _ ->
        heightOffset = value
    }
}
