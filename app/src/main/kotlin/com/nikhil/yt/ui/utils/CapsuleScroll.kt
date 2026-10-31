package com.nikhil.yt.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable

/**
 * One motion contract for every ordinary content list, grid and horizontal shelf.
 *
 * Compose's native spline decay owns both drag and fling. No extra velocity
 * multipliers, manual nested-scroll interception, per-frame coroutine launches,
 * or end-of-list rebound. Dedicated pagers keep their required page snapping.
 */
@Composable
fun rememberCapsuleFlingBehavior(): FlingBehavior = ScrollableDefaults.flingBehavior()

/**
 * A delayed navigation/reselection event must never steal the user's active
 * pointer gesture or an already-running fling. Consume the event first.
 */
fun mayProgrammaticallyScroll(isScrollInProgress: Boolean): Boolean = !isScrollInProgress

suspend fun LazyListState.scrollToTopIfIdle() {
    if (mayProgrammaticallyScroll(isScrollInProgress) &&
        (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0)
    ) animateScrollToItem(0)
}

suspend fun LazyGridState.scrollToTopIfIdle() {
    if (mayProgrammaticallyScroll(isScrollInProgress) &&
        (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0)
    ) animateScrollToItem(0)
}

suspend fun ScrollState.scrollToTopIfIdle() {
    if (mayProgrammaticallyScroll(isScrollInProgress) && value != 0) animateScrollTo(0)
}
