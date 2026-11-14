/* Capsule MUSIC — one physical timeline for the player window and its app scene. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal const val PlayerOpenDurationMs = 460
internal const val PlayerCloseDurationMs = 360
internal val PlayerOpenAnimation = tween<Float>(PlayerOpenDurationMs, easing = CubicBezierEasing(0.30f, 0.10f, 0.35f, 1f))
internal val PlayerCloseAnimation = tween<Float>(PlayerCloseDurationMs, easing = CubicBezierEasing(0.28f, 0.08f, 0.40f, 1f))

/** Action ownership is separate from Scaffold's subcomposition order. No clock or retained UI. */
class PlayerBackAction {
    internal var callback: (() -> Unit)? = null

    fun handle(fallback: () -> Unit) {
        val action = callback
        if (action != null) action() else fallback()
    }
}

/** Finite UI motion has no reason to wait for hidden display frames after Activity stop. */
@Composable
internal fun PlayerTransitionLifetime(state: BottomSheetState, onScreen: Boolean) {
    LaunchedEffect(onScreen, state.isAnimationRunning) {
        if (!onScreen && state.playerMotion && state.isAnimationRunning) {
            state.snapTo(when {
                state.isExpandedOrExpanding -> state.expandedBound
                state.isDismissedOrDismissing -> state.dismissedBound
                else -> state.collapsedBound
            })
        }
    }
}

private fun boundedProgress(progress: Float): Float =
    if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f

private fun window(progress: Float, start: Float, end: Float): Float {
    val t = ((boundedProgress(progress) - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The app stays stationary. Only a light scrim follows the existing window progress. */
internal fun playerSceneFocus(progress: Float): Float = window(progress, 0f, 1f)
internal fun playerSceneDim(progress: Float): Float = 0.16f * playerSceneFocus(progress)
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/** One continuous trip; the final corner pixels finish outside the viewport. */
internal fun playerFrameTravel(progress: Float): Float = (boundedProgress(progress) / 0.97f).coerceAtMost(1f)

/** Grow both card edges from Mini's bounds; the lower edge starts above app navigation. */
internal fun playerFrameHeight(progress: Float, fullHeight: Float, miniHeight: Float): Float {
    val full = fullHeight.coerceAtLeast(0f)
    val mini = miniHeight.coerceIn(0f, full)
    return mini + (full - mini) * playerFrameTravel(progress)
}

@Composable
internal fun PlayerSceneBackdrop(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    blocked: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize()
            .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
            .pointerInput(blocked) {
                if (!blocked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Read transition state in drawing/layer properties, not composition or measurement.
            // Once the opaque player covers the scene, even its draw recording can sleep.
            .drawWithContent { if (playerSceneNeedsDrawing(progress())) drawContent() },
    ) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val opacity = playerSceneDim(progress())
            if (opacity > 0f) drawRect(Color.Black.copy(alpha = opacity))
        }
    }
}

/** On a navigation-rail layout the rail remains a visible sibling pane. */
internal fun Modifier.playerRailFocus(progress: () -> Float): Modifier = drawWithContent {
    drawContent()
    val opacity = playerSceneDim(progress())
    if (opacity > 0f) drawRect(Color.Black.copy(alpha = opacity))
}

internal data class PlayerFrameContour(
    val inset: Float,
    val radius: Float,
    val bleed: Float,
)

/**
 * Corners never shrink to zero. During the last part of attachment the CLIP extends beyond the
 * window, carrying the rounded arcs behind its physical edges. A square display reveals those
 * last corner pixels progressively too. Media itself stays at its measured, undistorted size.
 */
internal fun playerFrameContour(progress: Float, miniInset: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    val attachment = playerFrameTravel(p)
    val radius = miniRadius + (26f * density - miniRadius) * attachment
    return PlayerFrameContour(
        inset = miniInset.coerceAtLeast(0f) * (1f - attachment),
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.97f, 1f),
    )
}

internal class PlayerCardShape(
    private val contour: PlayerFrameContour,
    private val visibleHeight: Float? = null,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = contour.inset - contour.bleed
        val right = size.width - contour.inset + contour.bleed
        val top = -contour.bleed
        val bottom = (visibleHeight ?: size.height).coerceIn(0f, size.height) + contour.bleed
        val radius = contour.radius.coerceIn(0f, minOf(right - left, bottom - top).coerceAtLeast(0f) / 2f)
        if (radius == 0f) return Outline.Rectangle(Rect(left, top, right, bottom))
        // A native round rectangle avoids rebuilding a bowed Path and its complex clip each frame.
        return Outline.Rounded(RoundRect(Rect(left, top, right, bottom), CornerRadius(radius, radius)))
    }
}
