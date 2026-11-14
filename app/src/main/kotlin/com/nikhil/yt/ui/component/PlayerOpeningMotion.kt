/* Capsule MUSIC — one physical timeline for the player window and its app scene. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.animation.core.spring
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

// One critically damped mutation preserves velocity when Back/tap reverses the window.
// A nearby reversal settles sooner instead of replaying a full fixed-duration trip.
internal val PlayerOpenAnimation = spring<Float>(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 0.5f)
internal val PlayerCloseAnimation = spring<Float>(dampingRatio = 1f, stiffness = 850f, visibilityThreshold = 0.5f)

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
internal fun playerSceneDim(progress: Float): Float = 0.20f * window(progress, 0f, 0.72f)
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/** One continuous trip; the final corner pixels finish outside the viewport. */
internal fun playerFrameTravel(progress: Float): Float = boundedProgress(progress)

/** Only the page contents dissolve. The growing material shell never fades over the app. */
internal fun playerPageOpacity(progress: Float): Float = window(progress, 0.18f, 0.66f)
internal fun playerPageScale(progress: Float, minimumWidthFraction: Float = 0f): Float =
    maxOf(0.94f + 0.06f * window(progress, 0.18f, 1f), minimumWidthFraction.coerceIn(0f, 1f))
internal fun playerSourceSeal(progress: Float): Float = window(progress, 0f, 0.20f)

/** Mini is the opaque source UNDER the dissolving card, so the app cannot leak through it. */
internal fun playerMiniDrawAlpha(progress: Float): Float = 1f - window(progress, 0.015f, 0.20f)

/** Mini's bottom stays docked; only its top reaches slightly towards the descending card. */
internal fun playerMiniStretch(progress: Float, velocityFraction: Float): Float {
    val p = boundedProgress(progress)
    val speed = if (velocityFraction.isFinite()) (-velocityFraction).coerceAtLeast(0f) else 0f
    val contact = window(p, 0f, 0.06f) * (1f - window(p, 0.08f, 0.22f))
    return 0.045f * speed / (1f + speed) * contact
}

/** The shell changes aspect, while every photo/video/control shares ONE uniform page scale. */
internal fun playerWindowBounds(progress: Float, size: Size, miniHeight: Float, dockHeight: Float, inset: Float): Rect {
    val p = boundedProgress(progress)
    val width = size.width.coerceAtLeast(0f)
    val height = size.height.coerceAtLeast(0f)
    val top = (height - dockHeight.coerceIn(0f, height)) * (1f - p)
    val bottom = (height - dockHeight + miniHeight).coerceIn(0f, height) * (1f - p) + height * p
    val side = inset.coerceIn(0f, width / 2f) * (1f - p)
    return Rect(side, top, width - side, bottom.coerceAtLeast(top))
}

internal class PlayerWindowShape(private val bounds: Rect, private val radius: Float, private val bleed: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(bounds.left - bleed, bounds.top - bleed,
            bounds.right + bleed, bounds.bottom + bleed), CornerRadius(radius, radius)))
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
