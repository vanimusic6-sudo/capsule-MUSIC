package com.nikhil.yt.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Scroll-linked detail toolbar reveal shared by playlists and albums.
 *
 * Old code switched at firstVisibleItemIndex > 0 and then animated for 280ms.
 * On a fast fling, the entire bar appeared AFTER the hero had disappeared.
 * Instead, fade during the last part of the header's actual scroll, so the
 * title and its surface are already fully visible when the next item arrives.
 */
internal fun detailToolbarRevealFraction(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    headerHeightPx: Int,
    fadeDistancePx: Int,
    toolbarHeightPx: Int,
    forceVisible: Boolean = false,
): Float {
    if (forceVisible || firstVisibleItemIndex > 0) return 1f
    // First composition can precede LazyColumn's measurement: avoid a visible
    // title flash until the actual header size has been measured.
    if (headerHeightPx <= 0) return 0f
    val distance = fadeDistancePx.coerceAtLeast(1)
    val start = (headerHeightPx - toolbarHeightPx - distance).coerceAtLeast(0)
    val linear = ((firstVisibleItemScrollOffset - start).toFloat() / distance)
        .coerceIn(0f, 1f)
    // S-curve removes the jerk at the beginning AND at the end of the fade.
    return linear * linear * (3f - 2f * linear)
}

@Composable
internal fun detailToolbarReveal(
    state: LazyListState,
    forceVisible: Boolean,
    hasHeader: Boolean = true,
): Float {
    val density = LocalDensity.current
    val distance = with(density) { 170.dp.roundToPx() }
    val toolbar = with(density) { 56.dp.roundToPx() }
    val headerSize = state.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == 0 }?.size ?: 0
    return detailToolbarRevealFraction(
        firstVisibleItemIndex = state.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
        headerHeightPx = headerSize,
        fadeDistancePx = distance,
        toolbarHeightPx = toolbar,
        forceVisible = forceVisible || !hasHeader,
    )
}
