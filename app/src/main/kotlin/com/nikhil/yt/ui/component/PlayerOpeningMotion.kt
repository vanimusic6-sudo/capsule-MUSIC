/* Capsule MUSIC — one physical timeline for the player window and its app scene. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nikhil.yt.ui.motion.CapsuleMotion

// Critically damped physical travel. Compose carries initial velocity through
// drag release and direction changes. Threshold is expressed in sheet pixels.
internal const val PLAYER_OPEN_STIFFNESS = 270f
internal const val PLAYER_CLOSE_STIFFNESS = 225f
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

/** Home is never transformed or shaded: keep glass sampling and dock coordinates stable. */
internal fun playerSceneDim(progress: Float): Float = 0f
// Backdrop is sampled by the glass Mini. Scaling Home shifts that capture.
internal fun playerSceneScale(progress: Float): Float = 1f
internal fun playerFrameShadowDp(progress: Float): Float {
    val p = boundedProgress(progress)
    return 10f * 4f * p * (1f - p)
}
internal fun playerSceneNeedsDrawing(progress: Float): Boolean = boundedProgress(progress) < 1f

/** The destination is visible only within the whole-scene optical envelope. */
internal fun playerPageVisible(progress: Float): Boolean = playerPresentationOpacity(progress) > 0f
internal fun playerPageAcceptsInput(progress: Float): Boolean = boundedProgress(progress) >= 0.985f

/**
 * Below this threshold the destination is optically gone and the user is looking at Home
 * again. Release BOTH full-screen pointer owners at the same moment, rather than waiting
 * for the last sub-pixel of the closing spring (which made the first Home scroll stall).
 */
internal const val PlayerSceneTouchThreshold = 0.025f

/** The same release boundary is used by the Home blocker and destination player window. */
internal fun playerSceneShouldInterceptTouch(
    progress: Float,
    expanding: Boolean,
    dismissed: Boolean,
): Boolean = !dismissed && (expanding || boundedProgress(progress) > PlayerSceneTouchThreshold)

// All stages sample the SAME anchored position. On close the order is
// automatically reversed; there is no direction-dependent visual jump.
internal const val PLAYER_MINI_FADE_END = 0.76f
// First the lower edge reaches navigation; then both share the same displacement.
// The contact phase finishes while the full player is still mostly transparent.
internal const val PLAYER_NAV_CONTACT = 0.065f
internal const val PLAYER_NAV_HIDDEN = 0.29f
internal fun playerNavigationTuck(progress: Float): Float =
    playerSilkPhase(progress, PLAYER_NAV_CONTACT, PLAYER_NAV_HIDDEN)

/** Lower edge follows the physical nav boundary, not a second independent slide. */
internal fun playerLowerEdgeTravelPx(progress: Float, available: Float, navigationHeight: Float): Float {
    if (!available.isFinite() || available <= 0f) return 0f
    val nav = if (navigationHeight.isFinite()) navigationHeight.coerceIn(0f, available) else 0f
    if (nav <= 0f) return available * playerFrameGeometryProgress(progress)
    val gap = available - nav
    return gap * playerSilkPhase(progress, 0.008f, PLAYER_NAV_CONTACT) +
        nav * playerNavigationTuck(progress)
}
internal fun playerMiniDrawAlpha(progress: Float): Float =
    1f - playerSilkPhase(progress, 0.42f, PLAYER_MINI_FADE_END)
internal fun playerPresentationOpacity(progress: Float): Float =
    playerSilkPhase(progress, 0.08f, 0.60f)

/**
 * A small landing accent driven by actual closing velocity. It is zero at
 * both ends, continuous through a reversal, and never changes the sheet anchor.
 * Mini goes DOWN by at most 2.5 dp; the returning nav rises by at most 1.75 dp.
 */
internal fun playerDockLanding(progress: Float, velocityFraction: Float): Float {
    if (!velocityFraction.isFinite() || velocityFraction >= 0f) return 0f
    val t = playerPhase(progress, 0.002f, 0.025f)
    val lobe = 4f * t * (1f - t)
    val closingWeight = playerSilkPhase(-velocityFraction, 0f, 0.10f)
    return lobe * lobe * lobe * closingWeight
}

internal fun playerMiniTravelPx(progress: Float, dockTop: Float, density: Float, velocityFraction: Float): Float {
    if (!dockTop.isFinite() || !density.isFinite() || density <= 0f) return 0f
    val geometry = playerFrameGeometryProgress(progress)
    // A small elastic lead lets Mini move as its own surface instead of being
    // welded to the upper edge of the expanding page. Zero at the dock and
    // after the optical handoff; the same curve reverses without a jump.
    val t = playerPhase(progress, 0.025f, PLAYER_MINI_FADE_END)
    val bell = 4f * t * (1f - t)
    return -dockTop.coerceAtLeast(0f) * geometry -
        minOf(7f * density, dockTop.coerceAtLeast(0f) * 0.025f) * bell * bell * bell +
        2.5f * density * playerDockLanding(progress, velocityFraction)
}

internal fun playerNavigationTravelPx(progress: Float, distance: Float, density: Float, velocityFraction: Float): Float {
    if (!distance.isFinite() || !density.isFinite() || density <= 0f) return 0f
    return distance.coerceAtLeast(0f) * playerNavigationTuck(progress) -
        1.75f * density * playerDockLanding(progress, velocityFraction)
}

internal fun playerArtworkBackdropAlpha(progress: Float): Float = 1f
// One shared progress drives the spatial and optical handoff.
internal fun playerSoftPhase(progress: Float, start: Float, end: Float): Float {
    val t = playerPhase(progress, start, end)
    return t * t * (3f - 2f * t)
}

/** C2 optical/spatial handoff, with zero acceleration at both ends. */
internal fun playerSilkPhase(progress: Float, start: Float, end: Float): Float {
    val t = playerPhase(progress, start, end).toDouble()
    return (t * t * t * (10.0 + t * (-15.0 + 6.0 * t))).toFloat().coerceIn(0f, 1f)
}

// Opacity phases have soft endpoints, but geometry follows the physical progress.
// The compact material turns into the real destination, not a second moving card.
internal fun playerMaterialReveal(progress: Float): Float = playerSoftPhase(progress, 0.08f, 0.63f)
internal fun playerMiniMaterialAlpha(progress: Float): Float = 1f - playerSoftPhase(progress, 0.16f, 0.34f)
// All dock foreground transfers use the same material ownership, not unrelated fades.
internal fun playerSharedSourceAlpha(progress: Float): Float = 1f - playerPresentationOpacity(progress)
internal fun playerLayerAlpha(effective: Float, parent: Float): Float =
    if (parent <= 0.0001f) 0f else (effective / parent).coerceIn(0f, 1f)
internal fun playerMiniForegroundLayerAlpha(progress: Float): Float =
    playerLayerAlpha(playerSharedSourceAlpha(progress), playerMiniDrawAlpha(progress))
internal fun playerMiniBadgeAlpha(progress: Float): Float =
    playerLayerAlpha(1f - playerSoftPhase(progress, 0.035f, 0.16f), playerSharedSourceAlpha(progress))
internal fun playerSharedDestinationAlpha(progress: Float): Float = playerSoftPhase(progress, 0.975f, 0.995f)
// Keep the moving image behind the real artwork until the latter has acquired full opacity.
// The parent shell already contributes the source/destination handoff alpha.
internal fun playerSharedOverlayAlpha(progress: Float): Float = 1f - playerSoftPhase(progress, 0.995f, 1f)
internal fun playerAtomicSceneAlpha(progress: Float): Float = playerSoftPhase(progress, 0.12f, 0.68f)
internal fun playerContentOpacity(progress: Float): Float = playerSoftPhase(progress, 0.28f, 0.74f)
// Shared artwork is allowed to pass across the destination title/transport area.
// Keep that foreground out of its path until the cover is almost at its slot.
internal fun playerSharedForegroundOpacity(progress: Float): Float = playerSoftPhase(progress, 0.60f, 0.92f)
internal fun playerArtworkTravel(progress: Float, start: Float): Float {
    // Sample the actual aperture progress, including the compact handoff.
    // The cover must never start its long travel while the shell is still docked.
    return playerPhase(playerFrameGeometryProgress(progress), playerFrameGeometryProgress(start), 0.98f)
}
internal fun playerAtomicContentY(frameTop: Float): Float = if (frameTop.isFinite()) frameTop.coerceAtLeast(0f) else 0f

// Reuse the visible release pulse instead of a subpixel vibration.
internal fun playerMiniLift(progress: Float, density: Float): Float = 0f
internal fun playerMiniApproach(progress: Float): Float = CapsuleMotion.miniScaleX(progress)
internal fun playerMiniHeightScale(progress: Float): Float = CapsuleMotion.miniScaleY(progress)
internal fun playerPresentationScale(progress: Float): Float = 1f

internal fun playerPresentationContentY(
    progress: Float, frameTop: Float, density: Float,
): Float {
    if (!frameTop.isFinite() || !density.isFinite() || density <= 0f) return 0f
    return minOf(frameTop.coerceAtLeast(0f) * 0.07f, 14f * density) * (1f - boundedProgress(progress))
}

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
internal fun playerFrameGeometryProgress(progress: Float): Float {
    val p = boundedProgress(progress)
    // Integrate a smooth speed ramp at the dock instead of multiplying
    // position by another easing. The latter creates a second acceleration
    // just when the closing spring is supposed to be braking.
    // This mapping is C2 at both joins, with a bounded spatial derivative.
    val start = 0.025f
    val end = 0.085f
    val span = end - start
    val normalization = 1f - (start + end) * 0.5f
    if (p <= start) return 0f
    if (p >= end) return ((p - (start + end) * 0.5f) / normalization).coerceIn(0f, 1f)
    val t = (p - start) / span
    return span * t * t * t * (1f - 0.5f * t) / normalization
}

internal fun playerPresentationBounds(
    progress: Float,
    width: Float,
    height: Float,
    miniTop: Float,
    miniHeight: Float,
    miniInset: Float,
    miniRightInset: Float = miniInset,
    navigationHeight: Float = 0f,
): Rect {
    val p = playerFrameGeometryProgress(progress)
    val leftInset = miniInset.coerceIn(0f, width * 0.5f)
    val rightInset = miniRightInset.coerceIn(0f, width * 0.5f)
    val sourceTop = miniTop.coerceIn(0f, height)
    val sourceBottom = (sourceTop + miniHeight).coerceIn(sourceTop, height)
    return Rect(
        left = leftInset * (1f - p),
        top = sourceTop * (1f - p),
        right = width - rightInset * (1f - p),
        bottom = sourceBottom + playerLowerEdgeTravelPx(progress, height - sourceBottom, navigationHeight),
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
    val currentProgress by rememberUpdatedState(progress)
    val drawHome by remember { derivedStateOf { playerSceneNeedsDrawing(currentProgress()) } }
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
            .drawWithContent { if (drawHome) drawContent() },
    ) {
        content()
    }
}

/** On a navigation-rail layout the rail remains a visible sibling pane. */
internal fun Modifier.playerRailFocus(progress: () -> Float): Modifier = this

internal data class PlayerFrameContour(
    val radius: Float,
    val bleed: Float,
)

/** The mini's round corners follow the growing frame, then disappear outside the viewport. */
internal fun playerPageContour(progress: Float, miniRadius: Float, density: Float): PlayerFrameContour {
    val p = boundedProgress(progress)
    val radius = (miniRadius + (maxOf(miniRadius, 24f * density) - miniRadius) * playerSilkPhase(p, 0f, 0.45f)) *
        (1f - playerSilkPhase(p, 0.64f, 1f))
    return PlayerFrameContour(
        radius = radius.coerceAtLeast(0f),
        // At ~0.293r of diagonal overscan the circular arc is completely outside a square
        // viewport. Keep the rounding visible until the frame itself has reached the screen.
        bleed = (radius.coerceAtLeast(0f) * 0.30f + density) * window(p, 0.75f, 1f),
    )
}
