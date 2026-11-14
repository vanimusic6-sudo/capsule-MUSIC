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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal const val PlayerOpenDurationMs = 600
internal const val PlayerCloseDurationMs = 460
internal val PlayerOpenAnimation = tween<Float>(PlayerOpenDurationMs, easing = CubicBezierEasing(0.24f, 0f, 0.38f, 1f))
internal val PlayerCloseAnimation = tween<Float>(PlayerCloseDurationMs, easing = CubicBezierEasing(0.28f, 0f, 0.44f, 1f))

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

/** The underlay recedes once; it owns no Animatable, timer, blur or alpha buffer. */
internal fun playerSceneFocus(progress: Float): Float = window(progress, 0f, 0.72f)
internal fun playerSceneScale(progress: Float): Float = 1f - 0.024f * playerSceneFocus(progress)
internal fun playerSceneDim(progress: Float): Float = 0.30f * playerSceneFocus(progress)
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/** Smooth edge catch; the last 3% carries only the corners past the window, not the content. */
internal fun playerFrameTravel(progress: Float): Float {
    val p = boundedProgress(progress)
    if (p <= 0.80f) return p
    if (p >= 0.97f) return 1f
    val t = (p - 0.80f) / 0.17f
    // Hermite tangents match the incoming movement and reach zero velocity at contact.
    val slope = 0.17f / 0.20f
    return 0.80f + 0.20f * ((slope - 2f) * t * t * t + (3f - 2f * slope) * t * t + slope * t)
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
            .drawWithContent { if (playerSceneNeedsDrawing(progress())) drawContent() }
            .graphicsLayer {
                val focus = playerSceneFocus(progress())
                scaleX = playerSceneScale(progress())
                scaleY = scaleX
                shape = if (focus > 0f) RoundedCornerShape(26.dp * focus) else RectangleShape
                clip = focus > 0f
            },
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
    val bend: Float,
)

/**
 * Corners never shrink to zero. During the last part of attachment the CLIP extends beyond the
 * window, carrying the rounded arcs behind its physical edges. A square display reveals those
 * last corner pixels progressively too. Media itself stays at its measured, undistorted size.
 */
internal fun playerFrameContour(progress: Float, miniInset: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    val attachment = window(p, 0.52f, 0.97f)
    val radius = miniRadius + (26f * density - miniRadius) * window(p, 0.05f, 0.45f)
    return PlayerFrameContour(
        inset = miniInset.coerceAtLeast(0f) * (1f - attachment),
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.97f, 1f),
        bend = 3.5f * density * sin(PI.toFloat() * attachment).coerceAtLeast(0f),
    )
}

internal class PlayerMagneticFrameShape(private val contour: PlayerFrameContour) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = contour.inset - contour.bleed
        val right = size.width - contour.inset + contour.bleed
        val top = -contour.bleed
        val bottom = size.height + contour.bleed
        val radius = contour.radius.coerceIn(0f, minOf(right - left, bottom - top).coerceAtLeast(0f) / 2f)
        if (radius == 0f && contour.bend == 0f) return Outline.Rectangle(Rect(left, top, right, bottom))
        val k = 0.55228475f * radius
        val span = (bottom - top - 2f * radius).coerceAtLeast(0f)
        return Outline.Generic(Path().apply {
            moveTo(left + radius, top)
            lineTo(right - radius, top)
            cubicTo(right - radius + k, top, right, top + radius - k, right, top + radius)
            // The middle of each edge reaches for the screen before its corners catch up.
            cubicTo(right + contour.bend, top + radius + span / 3f,
                right + contour.bend, bottom - radius - span / 3f, right, bottom - radius)
            cubicTo(right, bottom - radius + k, right - radius + k, bottom, right - radius, bottom)
            lineTo(left + radius, bottom)
            cubicTo(left + radius - k, bottom, left, bottom - radius + k, left, bottom - radius)
            cubicTo(left - contour.bend, bottom - radius - span / 3f,
                left - contour.bend, top + radius + span / 3f, left, top + radius)
            cubicTo(left, top + radius - k, left + radius - k, top, left + radius, top)
            close()
        })
    }
}
