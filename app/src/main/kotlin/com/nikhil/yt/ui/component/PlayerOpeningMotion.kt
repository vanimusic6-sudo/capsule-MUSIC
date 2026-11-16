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
import com.nikhil.yt.ui.motion.CapsuleMotion

// Spring motion remains tied to the ONE anchored sheet. Apple's WWDC23 continuity principle:
// a reversal keeps the current position AND velocity. Near-critical damping is deliberately
// non-bouncy, with a 1px finish threshold to avoid a visible last-frame snap.
internal val PlayerOpenAnimation = spring<Float>(dampingRatio = 1f, stiffness = 236f, visibilityThreshold = 1f)
internal val PlayerCloseAnimation = spring<Float>(dampingRatio = 1f, stiffness = 268f, visibilityThreshold = 1f)

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
    return CapsuleMotion.smooth(t)
}

/** The app stays put. A very quiet scrim is behind the actual moving shape, never over it. */
internal fun playerSceneDim(progress: Float): Float = 0.07f * boundedProgress(progress)
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/**
 * DESIGN: matched CONTAINER bounds, not a miniature full-screen UI.
 *
 * The real compact bar is the source. The destination shares its changing contour, while its
 * content waits until that contour has ROOM for it. No full-page fade, no stretched photographs
 * and no independent artwork clock; the single anchored spring supplies every input.
 */
internal fun playerPageVisible(progress: Float): Boolean = boundedProgress(progress) > 0f
internal fun playerPageAcceptsInput(progress: Float): Boolean = boundedProgress(progress) >= 0.90f

// Outgoing Mini remains the real button/artwork, at the real dock. A tiny vertical approach keeps
// it attached to the expanding material; it does NOT fly with the top edge to the status bar.
internal fun playerMiniDrawAlpha(progress: Float): Float = 1f - window(progress, 0.12f, 0.52f)
internal fun playerMiniLift(progress: Float, density: Float): Float =
    -6f * density * window(progress, 0.06f, 0.48f)
internal fun playerMiniApproach(progress: Float): Float = 1f

// The mask is opaque on its FIRST visible frame; only the content fades INSIDE the mask.
// This prevents the ghost Home/white cover visible at 0.25–0.45s in 12890.mp4.
internal fun playerPresentationOpacity(progress: Float): Float =
    if (boundedProgress(progress) > 0f) 1f else 0f
internal fun playerContentOpacity(progress: Float): Float = window(progress, 0.30f, 0.72f)
internal fun playerPresentationScale(progress: Float): Float = 1f
internal fun playerOrganicBackdropOpacity(progress: Float): Float = window(progress, 0.40f, 0.79f)

// Unlike the previous version, the actual full-size artwork never scales. Its natural layout
// travels UP with the moving frame's top edge (and ends at exact translation 0).
internal fun playerPresentationContentY(
    progress: Float, frameTop: Float, density: Float,
): Float = frameTop

/**
 * Coordinates are in the root player's viewport, not in a separately measured overlay.
 * The source is the REAL compact bar (including its bottom-navigation gap), and the target is
 * EXACTLY (0, 0, width, height). Progress is reused on closing, including mid-flight reversal.
 */
internal fun playerPresentationBounds(
    progress: Float,
    width: Float,
    height: Float,
    miniTop: Float,
    miniHeight: Float,
    miniInset: Float,
): Rect {
    val p = boundedProgress(progress)
    val inset = miniInset.coerceIn(0f, width * 0.5f)
    val sourceTop = miniTop.coerceIn(0f, height)
    val sourceBottom = (sourceTop + miniHeight).coerceIn(sourceTop, height)
    return Rect(
        left = inset * (1f - p),
        top = sourceTop * (1f - p),
        right = width - inset * (1f - p),
        bottom = sourceBottom + (height - sourceBottom) * p,
    )
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

/** Exactly the compact card's radius at the start, exactly square fullscreen at the end. */
internal fun playerPageContour(progress: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    return PlayerFrameContour(
        radius = miniRadius.coerceAtLeast(0f) * (1f - p),
        bleed = 0f,
    )
}
