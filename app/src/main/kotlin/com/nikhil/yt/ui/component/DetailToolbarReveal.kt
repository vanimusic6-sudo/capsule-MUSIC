package com.nikhil.yt.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity

/**
 * One scroll-driven photo / pinned-toolbar transition for albums and all
 * playlists. Titles live ONLY in the pinned top bar, as on ArtistScreen.
 *
 * Measure the actual artwork's resting bottom edge, including any artwork
 * inset, rather than guessing the title/caption height or assuming one
 * device size. Drawing samples scroll state without a per-frame animation.
 * Artist, album and playlist pages share the same fade function.
 */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
) {
    var hasHeader: Boolean = true

    private var restArtworkBottomPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)

    fun measureArtwork(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val bottom = coordinates.positionInRoot().y +
            coordinates.size.height + scroll.firstVisibleItemScrollOffset
        if (bottom.isFinite() &&
            (restArtworkBottomPx.isNaN() || kotlin.math.abs(bottom - restArtworkBottomPx) > 0.5f)
        ) {
            restArtworkBottomPx = bottom
        }
    }

    fun measureToolbar(coordinates: LayoutCoordinates) {
        val bottom = coordinates.positionInRoot().y + coordinates.size.height
        if (bottom.isFinite() &&
            (toolbarBottomPx.isNaN() || kotlin.math.abs(bottom - toolbarBottomPx) > 0.5f)
        ) {
            toolbarBottomPx = bottom
        }
    }

    fun artworkFade(): Float {
        if (!hasHeader) return 1f
        return detailArtworkFadeLikeArtist(
            firstVisibleItemIndex = scroll.firstVisibleItemIndex,
            offsetPx = scroll.firstVisibleItemScrollOffset,
            artworkBottomPx = restArtworkBottomPx,
            toolbarBottomPx = toolbarBottomPx,
        )
    }

    fun artworkAlpha(): Float = 1f - artworkFade()
}

/**
 * A long, geometry-based dissolve, shared with the artist page.
 * Keep the fade complete by the time artwork reaches the pinned bar. A photo
 * that disappears later would expose an abrupt boundary below the toolbar.
 */
internal fun detailArtworkFadeLikeArtist(
    firstVisibleItemIndex: Int,
    offsetPx: Int,
    artworkBottomPx: Float,
    toolbarBottomPx: Float,
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (!artworkBottomPx.isFinite() || !toolbarBottomPx.isFinite()) return 0f
    val distance = (artworkBottomPx - toolbarBottomPx).coerceAtLeast(1f)
    return portraitDissolveFraction(offsetPx.toFloat(), distance)
}

/**
 * Unified motion for artist portrait / album cover / playlist artwork.
 * Begin after the initial 12% of scrolling and dissolve through 99% of
 * the image-to-toolbar travel, versus the old artist's 0..74%.
 *
 * Finishing AFTER the image's edge crosses the bar creates a sharp seam and
 * translucent controls; 99% is the latest safe full-dissolve threshold.
 */
internal fun portraitDissolveFraction(offsetPx: Float, distancePx: Float): Float {
    val distance = distancePx.coerceAtLeast(1f)
    val start = distance * 0.12f
    val end = distance * 0.99f
    val fraction = ((offsetPx - start) / (end - start)).coerceIn(0f, 1f)
    // Evaluate the polynomial in Double: Float32 cancellation near t=1
    // caused the last frames to wiggle backward (and fail monotonic tests).
    // The returned draw alpha is still Float and stays strictly in [0, 1].
    val t = fraction.toDouble()
    return (t * t * t * (t * (t * 6.0 - 15.0) + 10.0)).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun rememberDetailHeaderMotion(
    state: LazyListState,
    hasHeader: Boolean,
): DetailHeaderMotionState {
    val motion = remember(state) { DetailHeaderMotionState(state) }
    SideEffect { motion.hasHeader = hasHeader }
    return motion
}

/** Reuse the EXACT artist toolbar surface policy: never double-tint the photo. */
internal fun detailToolbarBackdropAlpha(
    photoDissolve: Float,
    forceOpaque: Boolean = false,
): Float = if (forceOpaque) 1f else artistToolbarSurfaceAlpha(photoDissolve)

/** The photo fades in the hero, and only then the bar paints the page surface. */
internal fun Modifier.artistStyleDetailBackdrop(
    motion: DetailHeaderMotionState,
    surfaceColor: Color,
    forceOpaque: Boolean = false,
): Modifier = drawBehind {
    drawRect(surfaceColor.copy(alpha = detailToolbarBackdropAlpha(
        photoDissolve = motion.artworkFade(),
        forceOpaque = forceOpaque,
    )))
}
