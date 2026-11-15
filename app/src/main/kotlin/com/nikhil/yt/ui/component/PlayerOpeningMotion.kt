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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
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

/** The page paints opaquely as soon as it enters. Never crossfade a whole page over the app. */
internal fun playerPageVisible(progress: Float): Boolean = boundedProgress(progress) > 0f
internal fun playerPageAcceptsInput(progress: Float): Boolean = boundedProgress(progress) >= 0.72f

/** Only non-shared compact controls retire; artwork/title have their own matched paths. */
internal fun playerMiniDrawAlpha(progress: Float): Float = 1f - window(progress, 0.04f, 0.28f)

internal fun playerBodyOpacity(progress: Float): Float = window(progress, 0.22f, 0.72f)

/** Geometric source edge used by compact hit-testing and fallback bounds. */
internal fun playerSurfaceTop(progress: Float, fullHeight: Float, dockHeight: Float): Float =
    (fullHeight - dockHeight.coerceIn(0f, fullHeight.coerceAtLeast(0f))) * (1f - boundedProgress(progress))

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
            // Keep the modifier/node topology stable while semantics queries force remeasure.
            .semantics { if (blocked) hideFromAccessibility() }
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
    val radius: Float,
    val bleed: Float,
)

/**
 * Corners never shrink to zero. During the last part of attachment the CLIP extends beyond the
 * window, carrying the rounded arcs behind its physical edges. A square display reveals those
 * last corner pixels progressively too. Media itself stays at its measured, undistorted size.
 */
internal fun playerPageContour(progress: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    val attachment = p
    val radius = miniRadius + (26f * density - miniRadius) * attachment
    return PlayerFrameContour(
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.97f, 1f),
    )
}
