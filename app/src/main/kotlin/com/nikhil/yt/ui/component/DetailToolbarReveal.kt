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
 * A longer, later dissolve than the former 74%-distance transition.
 * Both detail layouts use the SAME measured photo bottom, not an estimated
 * title or hero height. It starts at 12% and finishes at 98% of the distance
 * between the photo's lower edge and the pinned toolbar's lower edge.
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
    val start = distance * 0.12f
    val end = distance * 0.98f
    val fraction = ((offsetPx - start) / (end - start)).coerceIn(0f, 1f)
    // Fifth-order easing keeps both the acceleration and velocity near zero
    // on entry/exit; photo, toolbar surface and icon tint share this value.
    return fraction * fraction * fraction * (fraction * (fraction * 6f - 15f) + 10f)
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
