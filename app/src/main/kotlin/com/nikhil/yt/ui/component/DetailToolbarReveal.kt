package com.nikhil.yt.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

/**
 * A single, measured geometry source for the album and all playlist headers.
 * No guessed cover dimensions, subtitle offsets, or index-driven toolbar fade.
 *
 * Both titles and the artwork are driven by the same actual title-to-toolbar
 * distance. Scroll offset is read only from graphicsLayer (draw phase), while
 * title and toolbar positions are remembered in their rest positions (layout
 * measurements update only when the physical geometry changes).
 */
internal class DetailHeaderMotionState(
    private val scroll: LazyListState,
    private val pxPerDp: Float,
) {
    var hasHeader: Boolean = true

    private var restTitleTopPx by mutableFloatStateOf(Float.NaN)
    private var toolbarBottomPx by mutableFloatStateOf(Float.NaN)

    fun measureTitle(coordinates: LayoutCoordinates) {
        if (scroll.firstVisibleItemIndex != 0) return
        val measured = coordinates.positionInRoot().y + scroll.firstVisibleItemScrollOffset
        if (!measured.isNaN() && (restTitleTopPx.isNaN() || kotlin.math.abs(restTitleTopPx - measured) > 0.5f)) {
            restTitleTopPx = measured
        }
    }

    fun measureToolbar(coordinates: LayoutCoordinates) {
        val measured = coordinates.positionInRoot().y + coordinates.size.height
        if (!measured.isNaN() && (toolbarBottomPx.isNaN() || kotlin.math.abs(toolbarBottomPx - measured) > 0.5f)) {
            toolbarBottomPx = measured
        }
    }

    fun fractions(): DetailHeaderFractions {
        if (!hasHeader || scroll.firstVisibleItemIndex > 0) return detailHeaderFractions(Float.NEGATIVE_INFINITY, pxPerDp)
        val title = restTitleTopPx
        val toolbar = toolbarBottomPx
        if (title.isNaN() || toolbar.isNaN()) return detailHeaderFractions(Float.POSITIVE_INFINITY, pxPerDp)
        return detailHeaderFractions(title - scroll.firstVisibleItemScrollOffset - toolbar, pxPerDp)
    }

    fun artworkAlpha(): Float = 1f - fractions().photoDissolve
    fun sourceTitleAlpha(): Float = fractions().sourceTitle
    fun toolbarTitleAlpha(): Float = fractions().pinnedTitle

    fun originalTitleModifier(): Modifier = Modifier
        .onGloballyPositioned(::measureTitle)
        .graphicsLayer { alpha = sourceTitleAlpha() }
}

internal data class DetailHeaderFractions(
    val photoDissolve: Float,
    val sourceTitle: Float,
    val pinnedTitle: Float,
)

private fun smoothstep(from: Float, to: Float, value: Float): Float {
    val t = ((from - value) / (from - to)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Smooth photo dissolve across 286 dp, finishing just after the real header
 * title crosses the toolbar. The two title opacities NEVER overlap.
 *
 * There is intentionally no rectangular toolbar scrim at any progress:
 * the whole page and the transparent pinned bar share the same true surface
 * colour, so no horizontal seam can appear at the toolbar's lower edge.
 */
internal fun detailHeaderFractions(titleDistancePx: Float, pxPerDp: Float): DetailHeaderFractions {
    val d = titleDistancePx / pxPerDp.coerceAtLeast(0.001f)
    val photo = smoothstep(260f, -26f, d)
    val source = 1f - smoothstep(74f, -24f, d)
    val pinned = smoothstep(-24f, -70f, d)
    return DetailHeaderFractions(photo, source, pinned)
}

@Composable
internal fun rememberDetailHeaderMotion(
    state: LazyListState,
    hasHeader: Boolean,
): DetailHeaderMotionState {
    val density = LocalDensity.current.density
    val motion = remember(state, density) { DetailHeaderMotionState(state, density) }
    SideEffect { motion.hasHeader = hasHeader }
    return motion
}
