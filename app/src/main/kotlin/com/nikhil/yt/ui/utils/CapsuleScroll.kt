package com.nikhil.yt.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.dp
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
 * Suspend nested horizontal shelves only while the vertical owner is actively
 * under the finger. Do NOT use isScrollInProgress: that also includes fling,
 * and made a fresh horizontal swipe unresponsive until the parent settled.
 *
 * Compose's row/column axis arbitration can otherwise let a shelf react while
 * a diagonal vertical drag is underway. The row retains its native drag/fling
 * whenever the parent is merely coasting or idle.
 */
@Composable
fun rememberCapsuleShelfScrollEnabled(parent: LazyListState): Boolean =
    !parent.interactionSource.collectIsDraggedAsState().value

@Composable
fun rememberCapsuleShelfScrollEnabled(parent: ScrollState): Boolean =
    !parent.interactionSource.collectIsDraggedAsState().value

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

/**
 * Only a small part of the visible edge may trigger reorder auto-scroll.
 * Full player-aware content padding is much larger than the actual drag
 * activation zone and can make holding a handle auto-scroll from mid-screen.
 */
@Composable
fun rememberCapsuleReorderEdgePadding(): androidx.compose.foundation.layout.PaddingValues {
    val bottomInset = com.nikhil.yt.LocalPlayerAwareWindowInsets.current
        .asPaddingValues().calculateBottomPadding()
    return androidx.compose.runtime.remember(bottomInset) {
        androidx.compose.foundation.layout.PaddingValues(
            bottom = bottomInset.coerceIn(0.dp, 56.dp),
        )
    }
}

/** Bound reorder auto-pan velocity, independent of list size and screen density. */
@Composable
fun rememberCapsuleReorderScroller(state: LazyListState): sh.calvin.reorderable.Scroller {
    val maxStepPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        28.dp.toPx()
    }
    return sh.calvin.reorderable.rememberScroller(
        scrollableState = state,
        pixelAmountProvider = {
            (state.layoutInfo.viewportSize.height.coerceAtLeast(0) * 0.018f)
                .coerceAtMost(maxStepPx)
        },
    )
}
