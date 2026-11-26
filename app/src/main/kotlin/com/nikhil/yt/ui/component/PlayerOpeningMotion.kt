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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nikhil.yt.ui.motion.CapsuleMotion

// Critically damped physical travel. Compose carries initial velocity through
// drag release and direction changes. Threshold is expressed in sheet pixels.
internal const val PLAYER_OPEN_STIFFNESS = 500f
internal const val PLAYER_CLOSE_STIFFNESS = 650f
internal val PlayerOpenAnimation = spring<Float>(
    dampingRatio = 1f, stiffness = PLAYER_OPEN_STIFFNESS, visibilityThreshold = 1.5f,
)
internal val PlayerCloseAnimation = spring<Float>(
    dampingRatio = 1f, stiffness = PLAYER_CLOSE_STIFFNESS, visibilityThreshold = 1.5f,
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

internal fun playerPhase(progress: Float, start: Float, end: Float): Float =
    ((boundedProgress(progress) - start) / (end - start).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

private fun window(progress: Float, start: Float, end: Float): Float =
    playerPhase(progress, start, end)

/** The app stays stationary; a restrained shadow gives the arriving surface depth. */
internal fun playerSceneDim(progress: Float): Float = 0.28f * window(progress, 0f, 0.84f)
internal fun playerSceneScale(progress: Float): Float = 1f - 0.04f * boundedProgress(progress)
internal fun playerFrameShadowDp(progress: Float): Float {
    val p = boundedProgress(progress)
    return 10f * 4f * p * (1f - p)
}
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

// All stages sample the SAME anchored position. On close the order is
// automatically reversed; there is no direction-dependent visual jump.
internal const val PLAYER_MINI_FADE_END = 0.52f
internal fun playerNavigationTuck(progress: Float): Float = window(progress, 0.005f, 0.27f)
internal fun playerMiniDrawAlpha(progress: Float): Float =
    1f - window(progress, 0.13f, PLAYER_MINI_FADE_END)
internal fun playerPresentationOpacity(progress: Float): Float = window(progress, 0f, 0.10f)
internal fun playerArtworkBackdropAlpha(progress: Float): Float = 1f
// Immersion includes its photograph in this layer: do not leave a solid grey
// shell exposed while waiting for metadata. Full content settles after the shell.
internal fun playerContentOpacity(progress: Float): Float = 1f

// Reuse the visible release pulse instead of a subpixel vibration.
internal fun playerMiniLift(progress: Float, density: Float): Float = 0f
internal fun playerMiniApproach(progress: Float): Float = CapsuleMotion.miniScaleX(progress)
internal fun playerMiniHeightScale(progress: Float): Float = CapsuleMotion.miniScaleY(progress)
internal fun playerPresentationScale(progress: Float): Float =
    0.94f + 0.06f * boundedProgress(progress)

internal fun playerPresentationContentY(
    progress: Float, frameTop: Float, density: Float,
): Float = frameTop * (1f - 0.35f * boundedProgress(progress))

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
internal fun playerFrameGeometryProgress(progress: Float): Float = boundedProgress(progress)

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
        left = inset * (1f - window(p, 0f, 0.60f)),
        top = sourceTop * (1f - p),
        right = width - inset * (1f - window(p, 0f, 0.60f)),
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
            .graphicsLayer {
                val scale = playerSceneScale(progress())
                scaleX = scale
                scaleY = scale
            }
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
    val radius = miniRadius * (1f - window(p, 0.40f, 0.92f))
    return PlayerFrameContour(
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.75f, 1f),
    )
}
