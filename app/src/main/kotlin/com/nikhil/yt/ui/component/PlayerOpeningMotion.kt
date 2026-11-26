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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nikhil.yt.ui.motion.CapsuleMotion

// One bounded timeline for both layouts: no overshoot or sub-pixel spring tail.
// A reversed request starts from the actual current anchor, not an endpoint.
internal const val PLAYER_OPEN_DURATION_MS = 280
internal const val PLAYER_CLOSE_DURATION_MS = 240
internal val PlayerOpenAnimation = tween<Float>(
    durationMillis = PLAYER_OPEN_DURATION_MS,
    easing = CubicBezierEasing(0.22f, 0.10f, 0.22f, 1f),
)
internal val PlayerCloseAnimation = tween<Float>(
    durationMillis = PLAYER_CLOSE_DURATION_MS,
    easing = CubicBezierEasing(0.28f, 0f, 0.36f, 1f),
)

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

/** The app stays stationary; a restrained shadow gives the arriving surface depth. */
internal fun playerSceneDim(progress: Float): Float = 0.16f * boundedProgress(progress)
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/**
 * The visible frame is a true mini-card -> fullscreen container transform. The page stays in
 * its final layout; only its GPU outline and a uniformly scaled content layer move.
 * No bitmap snapshot, placeholder plate, second colour field or stretched artwork.
 */
internal fun playerPageVisible(progress: Float): Boolean = boundedProgress(progress) > 0f
internal fun playerPageAcceptsInput(progress: Float): Boolean = boundedProgress(progress) >= 0.92f

/**
 * Below this threshold the destination is optically gone and the user is looking at Home
 * again. Release BOTH full-screen pointer owners at the same moment, rather than waiting
 * for the last sub-pixel of the closing spring (which made the first Home scroll stall).
 */
internal const val PlayerSceneTouchThreshold = 0.075f

/** The same release boundary is used by the Home blocker and destination player window. */
internal fun playerSceneShouldInterceptTouch(
    progress: Float,
    expanding: Boolean,
    dismissed: Boolean,
): Boolean = !dismissed && (expanding || boundedProgress(progress) > PlayerSceneTouchThreshold)

// The real mini starts returning before the closing window reaches its final dock.
// Both surfaces use the same anchor, so no late source swap or flash is introduced.
internal fun playerMiniDrawAlpha(progress: Float): Float = 1f - window(progress, 0.13f, 0.60f)
internal fun playerMiniLift(progress: Float, density: Float): Float =
    -2f * density * window(progress, 0f, 0.48f)
internal fun playerMiniApproach(progress: Float): Float =
    1f

// Let the real page content become readable slightly earlier as the Mini recedes.
// The contour still supplies the large size change; the small scale settle eases at both ends.
// Keep the content close to its natural size; the CONTOUR supplies the large size change.
// A 24% page scale made the artwork collapse into a detached, left-anchored vertical strip
// on device. The real mini card owns the first/last frames, not a tiny copy of the full UI.
internal fun playerPresentationOpacity(progress: Float): Float = window(progress, 0.015f, 0.56f)
internal fun playerContentOpacity(progress: Float): Float = window(progress, 0.11f, 0.66f)
// The real contour supplies the size transformation. The previous extra
// 5% scale on revealed artwork looked like a second card snapping into place.
// Keep a quiet 2.5% GPU settle, with no new layout or animation clock.
internal fun playerPresentationScale(progress: Float): Float =
    0.975f + 0.025f * CapsuleMotion.smooth(boundedProgress(progress))

// The near-full-size page follows the ascending top edge of the morphing frame. The small
// underlap keeps artwork IN the revealed card, not cropped to a one-sided strip. At rest both
// scale and translation are exactly identity. No separate horizontal translation at all.
internal fun playerPresentationContentY(
    progress: Float, frameTop: Float, density: Float,
): Float = frameTop - 10f * density * (1f - boundedProgress(progress))

/**
 * Coordinates are in the root player's viewport, not in a separately measured overlay.
 * The source is the REAL compact bar (including its bottom-navigation gap), and the target is
 * EXACTLY (0, 0, width, height). Progress is reused on closing, including mid-flight reversal.
 */
/**
 * An asymmetric contour: on close the whole window contracts toward Mini sooner, rather
 * than descending as an opaque fullscreen rectangle. It is reversible and monotonic,
 * and the same transform applies to a finger-driven transition or an interrupted spring.
 */
internal fun playerFrameGeometryProgress(progress: Float): Float = boundedProgress(progress).let { p ->
    // A gentle magnetic departure, with a continuous first derivative at both anchors.
    p - 0.12f * p * (1f - p)
}

internal fun playerPresentationBounds(
    progress: Float,
    width: Float,
    height: Float,
    miniTop: Float,
    miniHeight: Float,
    miniInset: Float,
): Rect {
    val p = playerFrameGeometryProgress(progress)
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
            // A passive pointerInput node still participates in hit-testing. Remove the
            // node entirely on close, so the underlying LazyColumn receives DOWN immediately.
            .then(if (blocked) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            } else Modifier)
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

/** The mini's round corners follow the growing frame, then disappear outside the viewport. */
internal fun playerPageContour(progress: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    val radius = miniRadius + (maxOf(miniRadius, 26f * density) - miniRadius) * window(p, 0f, 0.38f)
    return PlayerFrameContour(
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.88f, 1f),
    )
}
