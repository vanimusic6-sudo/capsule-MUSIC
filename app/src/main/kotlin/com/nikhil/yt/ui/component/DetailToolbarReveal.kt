package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
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


/**
 * Album/playlist covers use the artist card's scroll-tied smoothstep.
 * The actual artwork fades out BEFORE the top bar becomes opaque, so
 * the toolbar never double-tints a still-visible cover.
 */
internal fun detailArtworkFadeFraction(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    artworkBottomPx: Int,
    toolbarBottomPx: Int,
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (artworkBottomPx <= 0) return 0f
    val distance = (artworkBottomPx - toolbarBottomPx).coerceAtLeast(1) * 0.74f
    val t = (firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun rememberDetailArtworkFade(
    state: LazyListState,
    isPlaylist: Boolean,
    hasHeader: Boolean,
    withSubtitle: Boolean = false,
): () -> Float {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val statusHeight = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    return remember(state, density, screenHeight, statusHeight, isPlaylist, hasHeader, withSubtitle) {
        {
            if (!hasHeader) {
                1f
            } else {
                val viewportWidthPx = state.layoutInfo.viewportSize.width
                if (viewportWidthPx <= 0) {
                    0f // first composition is not measured; no title flash
                } else {
                    val width = with(density) { viewportWidthPx.toDp() }
                    val albumHeight = albumCoverHeight(width.coerceAtMost(560.dp), screenHeight)
                    val artworkBottom = if (isPlaylist) {
                        val squareSide = (width - 48.dp).coerceAtLeast(1.dp)
                        val topGap = (playlistArtworkTopGap(albumHeight, squareSide) -
                            if (withSubtitle) 22.dp else 0.dp).coerceAtLeast(22.dp)
                        topGap + squareSide
                    } else albumHeight
                    detailArtworkFadeFraction(
                        state.firstVisibleItemIndex,
                        state.firstVisibleItemScrollOffset,
                        with(density) { artworkBottom.roundToPx() },
                        with(density) { (statusHeight + 64.dp).roundToPx() },
                    )
                }
            }
        }
    }
}

/** Artist-style one-time surface handoff, after the cover has fully dissolved. */
internal fun detailToolbarSurfaceAlpha(fade: Float): Float = if (fade >= 1f) 1f else 0f
