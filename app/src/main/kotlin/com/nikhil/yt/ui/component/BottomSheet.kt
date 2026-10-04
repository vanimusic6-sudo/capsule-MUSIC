/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nikhil.yt.ui.motion.CapsuleMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.abs

/**
 * Lets a mounted child keep its state while suspending purely decorative procedural clocks.
 * The mini-player uses this while it is completely covered by the expanded full player.
 */
internal val LocalCapsuleBackgroundMotionEnabled = compositionLocalOf { true }

/**
 * Whether the mini-player's decorative background clock is allowed to run.
 *
 * Named because the terms are easy to lose and expensive to lose. The mini-player is on screen on
 * every page of the app for as long as something is playing, so its clock is the one that keeps the
 * frame clock awake during ordinary use. It has no business running where nobody can see it: not
 * behind the fully expanded player, and not once the sheet has been dismissed, where it used to
 * carry on drawing against nothing at all.
 */
internal fun miniPlayerClockShouldRun(
    isExpanded: Boolean,
    isDismissed: Boolean,
): Boolean = !isExpanded && !isDismissed

/** Sub-pixel at every density: only a rest that is already invisible counts as being on an anchor. */
internal const val ANCHOR_EPSILON_DP = 0.05f
internal const val SHEET_PROGRESS_EPSILON = 0.0001f

/**
 * Whether a sheet resting at [value] should be treated as sitting on [anchor].
 *
 * Exact equality assumes a sheet only ever stops because an animation finished on its target. It
 * also stops when a settle is interrupted — a drag caught mid-animation, bounds changing under it —
 * and then rests a fraction of a dp away. That is invisible, but it used to leave `isCollapsed`
 * false for good, and the player's BackHandler is armed on exactly that: the first Back press then
 * ran collapseSoft() on an already-collapsed sheet, travelled those few hundredths of a dp, and was
 * swallowed. Pressing Back twice to leave a screen is that bug.
 */
internal fun isAtSheetAnchor(
    value: Dp,
    anchor: Dp,
): Boolean =
    (value - anchor).value.absoluteValue <= ANCHOR_EPSILON_DP

/**
 * Player transition policy.
 *
 * Physical motion has one source: the anchored sheet progress. Visual ownership is split at one
 * handoff point so Mini UI and full Player UI are never visible at the same time. The moving
 * container stays present through that handoff, which prevents an empty frame.
 *
 * This follows the useful part of ArchiveTune's current BottomSheet: compact content yields before
 * expanded content appears, while the sheet/background itself remains continuous.
 */
internal const val PlayerContentHandoffPoint = 0.25f
internal const val FullPlayerContentFadeEnd = 0.50f

private fun transitionWindow(
    progress: Float,
    start: Float,
    end: Float,
): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val span = (end - start).coerceAtLeast(0.0001f)
    return CapsuleMotion.smooth(((p - start) / span).coerceIn(0f, 1f))
}

/** Entire compact UI, including artwork and controls. */
internal fun miniPlayerContentAlpha(progress: Float): Float =
    (1f - transitionWindow(
        progress = progress,
        start = 0f,
        end = PlayerContentHandoffPoint,
    )).coerceIn(0f, 1f)

/** Full Player UI starts only after compact UI has completely yielded. */
internal fun fullPlayerContentAlpha(progress: Float): Float =
    transitionWindow(
        progress = progress,
        start = PlayerContentHandoffPoint,
        end = FullPlayerContentFadeEnd,
    )

/** Non-interactive shared container that bridges the content handoff. */
internal fun playerContainerAlpha(progress: Float): Float =
    transitionWindow(
        progress = progress,
        start = 0f,
        end = PlayerContentHandoffPoint,
    )

internal fun shouldRenderExpandedSurface(
    rawProgress: Float,
    isDismissed: Boolean,
): Boolean {
    if (isDismissed) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    return p > SHEET_PROGRESS_EPSILON
}

internal fun shouldRenderExpandedContent(
    rawProgress: Float,
    isDismissed: Boolean,
): Boolean {
    if (isDismissed) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    return p > PlayerContentHandoffPoint
}

internal fun shouldShowCompactSurface(
    rawProgress: Float,
    isDismissed: Boolean,
): Boolean {
    if (isDismissed) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 1f
    return p < PlayerContentHandoffPoint
}

internal fun miniPlayerForegroundCanAcceptInput(
    rawProgress: Float,
    isDismissed: Boolean,
): Boolean = shouldShowCompactSurface(rawProgress, isDismissed)

internal fun expandedPlayerCanAcceptInput(
    rawProgress: Float,
    isDismissed: Boolean,
): Boolean = shouldRenderExpandedContent(rawProgress, isDismissed)

/** Capsule navigation and the moving player sheet share the same upper-corner language. */
internal val PlayerFrameCornerRadius = 26.dp

internal fun playerFrameCornerRadius(
    progress: Float,
    collapsedRadius: Dp = PlayerFrameCornerRadius,
): Dp {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    val eased = CapsuleMotion.smooth(p).coerceIn(0f, 1f)
    return (collapsedRadius * (1f - eased)).coerceAtLeast(0.dp)
}

/**
 * Horizontal container morph from the actual Mini Player inset to full-screen width.
 *
 * This is a GPU transform, not a layout animation: the full player tree keeps stable measurement
 * while its physical shell widens around the top-centre origin.
 */
internal fun playerFrameHorizontalScale(
    progress: Float,
    widthPx: Float,
    collapsedInsetPx: Float,
): Float {
    if (!widthPx.isFinite() || widthPx <= 0f) return 1f
    val safeInset = collapsedInsetPx.coerceIn(0f, widthPx * 0.25f)
    val collapsedScale = ((widthPx - safeInset * 2f) / widthPx).coerceIn(0.5f, 1f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    val eased = CapsuleMotion.smooth(p).coerceIn(0f, 1f)
    return collapsedScale + (1f - collapsedScale) * eased
}

/**
 * One moving container, two independent content trees.
 *
 * The full Player sheet owns the physical trajectory. Mini Player rides the same trajectory and
 * hands visual ownership to the full surface through progress-derived opacity. Mini controls and
 * gesture ownership still remain independent from the full Player composable.
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    gesturesEnabled: Boolean = true,
    allowSwipeDismiss: Boolean = true,
    dismissOnlyFromCollapsed: Boolean = false,
    backHandlerEnabled: Boolean = true,
    collapsedContentHeight: Dp? = null,
    collapsedHorizontalInset: Dp = 0.dp,
    collapsedTopCornerRadius: Dp = PlayerFrameCornerRadius,
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    /* Keep the mini-player composition alive so Room-backed state and icon morph state survive. */
    val shouldComposeMini by
        remember(state, onDismiss) {
            derivedStateOf {
                onDismiss == null || !state.isDismissed
            }
        }
    val canReopen by
        remember(state, onDismiss) {
            derivedStateOf {
                (onDismiss == null || !state.isDismissed) &&
                    state.compactForegroundAcceptsInput
            }
        }
    val miniBackgroundMotionEnabled by
        remember(state) {
            derivedStateOf {
                miniPlayerClockShouldRun(state.isExpanded, state.isDismissed) &&
                    state.compactSurfaceVisible
            }
        }
    val renderExpandedSurface by
        remember(state) {
            derivedStateOf {
                shouldRenderExpandedSurface(state.rawProgress, state.isDismissed)
            }
        }
    val renderExpandedContent by
        remember(state) {
            derivedStateOf {
                shouldRenderExpandedContent(state.rawProgress, state.isDismissed)
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .offset {
                    val y =
                        (state.expandedBound - state.value)
                            .roundToPx()
                            .coerceAtLeast(0)
                    IntOffset(x = 0, y = y)
                }
                /*
                 * The sheet itself never clips. The rounded top belongs to the full player and is
                 * applied there.
                 *
                 * It used to be here, and it cut the mini-player: on its dock the sheet's own top
                 * edge lies exactly along the top of the card, and the swipe tilts that card about
                 * its bottom edge, so the rising corner crossed the boundary and was sliced off for
                 * the whole gesture. Gating this layer on whether the player was docked fixed the
                 * swipe and bought a worse problem — clipping switched on in a single frame the
                 * moment the sheet left its dock, which is a blink at the exact instant the player
                 * opens. A boundary that does not exist cannot be crossed badly.
                 */
                .then(
                    if (gesturesEnabled) {
                        Modifier.bottomSheetDraggable(
                            state = state,
                            onDismiss = if (allowSwipeDismiss) onDismiss else null,
                            dismissOnlyFromCollapsed = dismissOnlyFromCollapsed,
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (backHandlerEnabled && gesturesEnabled && state.isExpandedOrExpanding) {
            BackHandler(onBack = state::collapseSoft)
        }

        if (shouldComposeMini) {
            Box(
                modifier =
                    Modifier
                        // Ride the parent sheet itself. This is the ArchiveTune-style lift: Mini
                        // physically travels upward and the opacity handoff only softens the morph
                        // instead of replacing movement with a dissolve.
                        .graphicsLayer {
                            alpha = miniPlayerContentAlpha(state.rawProgress)
                        }
                        // Mini visually yields over the same moving frame. Input has its own
                        // progress gate, so an almost-gone Mini cannot steal full-player controls.
                        .zIndex(if (state.compactSurfaceVisible) 2f else 0f)
                        .clickable(
                            enabled = canReopen,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = state::expandSoft,
                        )
                        .fillMaxWidth()
                        .height(collapsedContentHeight ?: state.collapsedBound),
            ) {
                CompositionLocalProvider(
                    LocalCapsuleBackgroundMotionEnabled provides miniBackgroundMotionEnabled,
                ) {
                    collapsedContent()
                }
            }
        }

        if (renderExpandedSurface) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val raw = state.rawProgress.coerceIn(0f, 1f)
                            scaleX =
                                playerFrameHorizontalScale(
                                    progress = raw,
                                    widthPx = size.width,
                                    collapsedInsetPx = collapsedHorizontalInset.toPx(),
                                )
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            val topCornerRadius =
                                playerFrameCornerRadius(
                                    progress = raw,
                                    collapsedRadius = collapsedTopCornerRadius,
                                )
                            shape =
                                RoundedCornerShape(
                                    topStart = topCornerRadius,
                                    topEnd = topCornerRadius,
                                )
                            clip = topCornerRadius > 0.dp
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = playerContainerAlpha(state.rawProgress)
                            }
                            .background(backgroundColor),
                )

                if (renderExpandedContent) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = fullPlayerContentAlpha(state.rawProgress)
                                },
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

internal enum class SheetAnchor {
    Dismissed,
    Collapsed,
    Expanded,
}

internal const val BOTTOM_SHEET_POSITIONAL_THRESHOLD_FRACTION = 0.5f
internal val BottomSheetVelocityThreshold = 125.dp

private val BottomSheetSettleAnimationSpec: AnimationSpec<Float> =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 225f,
    )

private val BottomSheetSoftAnimationSpecPx: AnimationSpec<Float> =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

private fun SheetAnchor.legacyId(): Int =
    when (this) {
        SheetAnchor.Dismissed -> DISMISSED_ANCHOR
        SheetAnchor.Collapsed -> COLLAPSED_ANCHOR
        SheetAnchor.Expanded -> EXPANDED_ANCHOR
    }

private fun legacyAnchor(
    anchor: Int,
    hasDismissedAnchor: Boolean,
): SheetAnchor =
    when (anchor) {
        EXPANDED_ANCHOR -> SheetAnchor.Expanded
        DISMISSED_ANCHOR ->
            if (hasDismissedAnchor) SheetAnchor.Dismissed else SheetAnchor.Collapsed
        else -> SheetAnchor.Collapsed
    }

private fun buildBottomSheetAnchors(
    density: Density,
    dismissedBound: Dp,
    collapsedBound: Dp,
    expandedBound: Dp,
): DraggableAnchors<SheetAnchor> =
    with(density) {
        DraggableAnchors {
            if (!isAtSheetAnchor(dismissedBound, collapsedBound)) {
                SheetAnchor.Dismissed at dismissedBound.toPx()
            }
            SheetAnchor.Collapsed at collapsedBound.toPx()
            SheetAnchor.Expanded at expandedBound.toPx()
        }
    }

/**
 * One target resolver for every vertical release.
 *
 * Positive velocity opens (state offset grows); negative velocity closes. The values mirror
 * AnchoredDraggable's platform defaults: 125dp/s velocity threshold and half-distance positional
 * threshold. Mini no longer has a second set of release thresholds.
 */
internal fun resolveBottomSheetTarget(
    offsetPx: Float,
    dismissedPx: Float,
    collapsedPx: Float,
    expandedPx: Float,
    velocityPxPerSecond: Float,
    velocityThresholdPxPerSecond: Float,
    allowDismiss: Boolean,
): Int {
    val velocityThreshold = abs(velocityThresholdPxPerSecond)
    if (allowDismiss && dismissedPx < collapsedPx && offsetPx < collapsedPx) {
        if (velocityPxPerSecond <= -velocityThreshold) return DISMISSED_ANCHOR
        if (velocityPxPerSecond >= velocityThreshold) return COLLAPSED_ANCHOR
        val midpoint =
            dismissedPx +
                (collapsedPx - dismissedPx) * BOTTOM_SHEET_POSITIONAL_THRESHOLD_FRACTION
        return if (offsetPx < midpoint) DISMISSED_ANCHOR else COLLAPSED_ANCHOR
    }

    if (velocityPxPerSecond >= velocityThreshold) return EXPANDED_ANCHOR
    if (velocityPxPerSecond <= -velocityThreshold) return COLLAPSED_ANCHOR

    val midpoint =
        collapsedPx +
            (expandedPx - collapsedPx) * BOTTOM_SHEET_POSITIONAL_THRESHOLD_FRACTION
    return if (offsetPx >= midpoint) EXPANDED_ANCHOR else COLLAPSED_ANCHOR
}

internal interface BottomSheetDragScope {
    fun dragBy(deltaPx: Float)
}

@Stable
@OptIn(ExperimentalFoundationApi::class)
class BottomSheetState internal constructor(
    private val coroutineScope: CoroutineScope,
    private val anchoredState: AnchoredDraggableState<SheetAnchor>,
    private val density: Density,
    private val onAnchorChanged: (Int) -> Unit,
    dismissedBound: Dp,
    collapsedBound: Dp,
    expandedBound: Dp,
) {
    private val dismissedBoundState = mutableStateOf(dismissedBound)
    private val collapsedBoundState = mutableStateOf(collapsedBound)
    private val expandedBoundState = mutableStateOf(expandedBound)

    val dismissedBound: Dp
        get() = dismissedBoundState.value

    val collapsedBound: Dp
        get() = collapsedBoundState.value

    val expandedBound: Dp
        get() = expandedBoundState.value

    private val hasDismissedAnchor: Boolean
        get() = !isAtSheetAnchor(dismissedBound, collapsedBound)

    private fun offsetPx(): Float = anchoredState.requireOffset()

    val value: Dp
        get() = with(density) { offsetPx().toDp() }

    val isAnimationRunning: Boolean
        get() = anchoredState.isAnimationRunning

    /*
     * Semantic intent used by Back/navigation. Physical progress remains the only visual source.
     * This prevents a closing spring from leaving Player as owner after close is requested.
     */
    private var requestedAnchor by mutableStateOf(anchoredState.targetValue)

    val targetAnchor: Int
        get() = requestedAnchor.legacyId()

    val isDismissed by
        derivedStateOf {
            hasDismissedAnchor && isAtSheetAnchor(value, dismissedBound)
        }

    val isCollapsed by
        derivedStateOf {
            isAtSheetAnchor(value, collapsedBound)
        }

    val isExpanded by
        derivedStateOf {
            isAtSheetAnchor(value, expandedBound)
        }

    val rawProgress by
        derivedStateOf {
            val collapsedPx = with(density) { collapsedBound.toPx() }
            val expandedPx = with(density) { expandedBound.toPx() }
            val range = expandedPx - collapsedPx
            if (range <= 0f) {
                0f
            } else {
                (offsetPx() - collapsedPx) / range
            }
        }

    /**
     * The only Mini <-> Player transition progress. It is linear under the finger; animation specs
     * shape programmatic/settling motion instead of warping the same drag a second time.
     */
    val progress by
        derivedStateOf {
            rawProgress.coerceIn(0f, 1f)
        }

    val isExpandedOrExpanding: Boolean
        get() = requestedAnchor == SheetAnchor.Expanded

    val isCollapsedOrCollapsing: Boolean
        get() = requestedAnchor == SheetAnchor.Collapsed

    val isDismissedOrDismissing: Boolean
        get() = requestedAnchor == SheetAnchor.Dismissed

    val shouldLayerAboveCollapsedChrome by
        derivedStateOf {
            shouldRenderExpandedContent(rawProgress, isDismissed)
        }

    val compactSurfaceVisible by
        derivedStateOf {
            shouldShowCompactSurface(rawProgress, isDismissed)
        }

    val compactForegroundAcceptsInput by
        derivedStateOf {
            miniPlayerForegroundCanAcceptInput(rawProgress, isDismissed)
        }

    private fun resolveRequestedAnchor(anchor: SheetAnchor): SheetAnchor =
        if (anchor == SheetAnchor.Dismissed && !hasDismissedAnchor) {
            SheetAnchor.Collapsed
        } else {
            anchor
        }

    private fun requestAnchor(anchor: SheetAnchor): SheetAnchor {
        val resolved = resolveRequestedAnchor(anchor)
        requestedAnchor = resolved
        onAnchorChanged(resolved.legacyId())
        return resolved
    }

    internal fun updateBounds(
        newDismissedBound: Dp,
        newCollapsedBound: Dp,
        newExpandedBound: Dp,
    ) {
        if (
            newDismissedBound == dismissedBound &&
            newCollapsedBound == collapsedBound &&
            newExpandedBound == expandedBound
        ) {
            return
        }

        dismissedBoundState.value = newDismissedBound
        collapsedBoundState.value = newCollapsedBound
        expandedBoundState.value = newExpandedBound

        val newHasDismissedAnchor = !isAtSheetAnchor(newDismissedBound, newCollapsedBound)
        val requestedTarget =
            if (requestedAnchor == SheetAnchor.Dismissed && !newHasDismissedAnchor) {
                requestAnchor(SheetAnchor.Collapsed)
            } else {
                requestedAnchor
            }

        anchoredState.updateAnchors(
            newAnchors =
                buildBottomSheetAnchors(
                    density = density,
                    dismissedBound = newDismissedBound,
                    collapsedBound = newCollapsedBound,
                    expandedBound = newExpandedBound,
                ),
            newTarget = requestedTarget,
        )
    }

    private suspend fun animateToAnchor(
        target: SheetAnchor,
        animationSpec: AnimationSpec<Float>,
        initialVelocity: Float = anchoredState.lastVelocity,
        priority: MutatePriority = MutatePriority.Default,
    ) {
        val resolvedTarget = resolveRequestedAnchor(target)
        anchoredState.anchoredDrag(
            targetValue = resolvedTarget,
            dragPriority = priority,
        ) { anchors, latestTarget ->
            val targetOffset = anchors.positionOf(latestTarget)
            if (targetOffset.isNaN()) return@anchoredDrag

            val start = anchoredState.requireOffset()
            if (start == targetOffset) {
                dragTo(targetOffset, 0f)
                return@anchoredDrag
            }

            animate(
                initialValue = start,
                targetValue = targetOffset,
                initialVelocity = initialVelocity,
                animationSpec = animationSpec,
            ) { animatedValue, animatedVelocity ->
                dragTo(animatedValue, animatedVelocity)
            }
        }
    }

    private fun launchAnimation(
        target: SheetAnchor,
        animationSpec: AnimationSpec<Float>,
        priority: MutatePriority = MutatePriority.Default,
    ) {
        val requested = requestAnchor(target)
        coroutineScope.launch {
            if (requestedAnchor != requested) return@launch
            try {
                animateToAnchor(
                    target = requested,
                    animationSpec = animationSpec,
                    priority = priority,
                )
            } catch (_: CancellationException) {
                // A new user gesture or a newer programmatic request owns the same offset now.
            }
        }
    }

    fun collapse(animationSpec: AnimationSpec<Float>) {
        launchAnimation(SheetAnchor.Collapsed, animationSpec)
    }

    fun expand(animationSpec: AnimationSpec<Float>) {
        launchAnimation(SheetAnchor.Expanded, animationSpec)
    }

    private fun collapse() {
        launchAnimation(SheetAnchor.Collapsed, BottomSheetSettleAnimationSpec)
    }

    private fun expand() {
        launchAnimation(SheetAnchor.Expanded, BottomSheetSettleAnimationSpec)
    }

    fun collapseSoft() {
        launchAnimation(SheetAnchor.Collapsed, BottomSheetSoftAnimationSpecPx)
    }

    fun expandSoft() {
        launchAnimation(SheetAnchor.Expanded, BottomSheetSoftAnimationSpecPx)
    }

    fun dismiss() {
        launchAnimation(
            target = SheetAnchor.Dismissed,
            animationSpec = BottomSheetSettleAnimationSpec,
            // Playback disappearing / Year in Music is authoritative over touch input.
            priority = MutatePriority.PreventUserInput,
        )
    }

    fun snapTo(value: Dp) {
        val target =
            when {
                isAtSheetAnchor(value, expandedBound) -> SheetAnchor.Expanded
                isAtSheetAnchor(value, collapsedBound) -> SheetAnchor.Collapsed
                isAtSheetAnchor(value, dismissedBound) && hasDismissedAnchor -> SheetAnchor.Dismissed
                else -> SheetAnchor.Collapsed
            }

        val requested = requestAnchor(target)
        coroutineScope.launch {
            if (requestedAnchor != requested) return@launch
            try {
                anchoredState.anchoredDrag(
                    targetValue = requested,
                    dragPriority = MutatePriority.PreventUserInput,
                ) { anchors, latestTarget ->
                    val targetOffset = anchors.positionOf(latestTarget)
                    if (!targetOffset.isNaN()) dragTo(targetOffset)
                }
            } catch (_: CancellationException) {
                // Superseded by a newer authoritative snap.
            }
        }
    }

    /**
     * One user-input mutation owns the complete vertical drag. Starting it interrupts any settle
     * animation and continues from the exact current offset.
     */
    internal suspend fun dragUserInput(
        allowDismiss: Boolean,
        dragPriority: MutatePriority = MutatePriority.UserInput,
        block: suspend BottomSheetDragScope.() -> Unit,
    ) {
        anchoredState.anchoredDrag(dragPriority) { anchors ->
            val minOffset =
                if (allowDismiss && hasDismissedAnchor) {
                    anchors.positionOf(SheetAnchor.Dismissed)
                } else {
                    anchors.positionOf(SheetAnchor.Collapsed)
                }
            val maxOffset = anchors.positionOf(SheetAnchor.Expanded)

            val scope =
                object : BottomSheetDragScope {
                    override fun dragBy(deltaPx: Float) {
                        val next =
                            (anchoredState.requireOffset() + deltaPx)
                                .coerceIn(minOffset, maxOffset)
                        dragTo(next)
                    }
                }
            scope.block()
        }
    }

    /**
     * DraggableState requires a raw-delta escape hatch. Normal pointer gestures do not use it:
     * they enter [dragUserInput] and therefore share AnchoredDraggable's mutation lock.
     */
    internal fun dispatchRawUserDelta(
        deltaPx: Float,
        allowDismiss: Boolean,
    ): Float {
        val minOffset =
            with(density) {
                (
                    if (allowDismiss && hasDismissedAnchor) {
                        dismissedBound
                    } else {
                        collapsedBound
                    }
                ).toPx()
            }
        val maxOffset = with(density) { expandedBound.toPx() }
        val current = anchoredState.requireOffset()
        val next = (current + deltaPx).coerceIn(minOffset, maxOffset)
        return anchoredState.dispatchRawDelta(next - current)
    }

    internal suspend fun settleUserInput(
        velocity: Float,
        velocityThresholdPx: Float,
        allowDismiss: Boolean,
        onDismiss: (() -> Unit)?,
    ) {
        val targetId =
            resolveBottomSheetTarget(
                offsetPx = offsetPx(),
                dismissedPx = with(density) { dismissedBound.toPx() },
                collapsedPx = with(density) { collapsedBound.toPx() },
                expandedPx = with(density) { expandedBound.toPx() },
                velocityPxPerSecond = velocity,
                velocityThresholdPxPerSecond = velocityThresholdPx,
                allowDismiss = allowDismiss && onDismiss != null,
            )
        val target = requestAnchor(legacyAnchor(targetId, hasDismissedAnchor))

        animateToAnchor(
            target = target,
            animationSpec = BottomSheetSettleAnimationSpec,
            initialVelocity = velocity,
        )

        if (target == SheetAnchor.Dismissed && isDismissed) {
            onDismiss?.invoke()
        }
    }

    fun settle(onDismiss: (() -> Unit)? = null) {
        performFling(velocity = 0f, onDismiss = onDismiss)
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        val threshold = with(density) { BottomSheetVelocityThreshold.toPx() }
        coroutineScope.launch {
            try {
                settleUserInput(
                    velocity = velocity,
                    velocityThresholdPx = threshold,
                    allowDismiss = onDismiss != null,
                    onDismiss = onDismiss,
                )
            } catch (_: CancellationException) {
                // A new drag owns the offset; do not finish or invoke destructive dismissal.
            }
        }
    }

    /** Queue/content nested scroll uses the same anchored offset, not a second Animatable. */
    private fun dispatchNestedScrollDelta(deltaY: Float): Float =
        -anchoredState.dispatchRawDelta(-deltaY)

    val preUpPostDownNestedScrollConnection
        get() =
            object : NestedScrollConnection {
                var isTopReached = false

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (isExpanded && available.y < 0) {
                        isTopReached = false
                    }

                    return if (
                        isTopReached &&
                            available.y < 0 &&
                            source == NestedScrollSource.UserInput
                    ) {
                        val consumedY = dispatchNestedScrollDelta(available.y)
                        Offset(x = 0f, y = consumedY)
                    } else {
                        Offset.Zero
                    }
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!isTopReached) {
                        isTopReached = consumed.y == 0f && available.y > 0
                    }

                    return if (
                        isTopReached &&
                            source == NestedScrollSource.UserInput
                    ) {
                        val consumedY = dispatchNestedScrollDelta(available.y)
                        Offset(x = 0f, y = consumedY)
                    } else {
                        Offset.Zero
                    }
                }

                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (isTopReached) {
                        val stateVelocity = -available.y
                        try {
                            settleUserInput(
                                velocity = stateVelocity,
                                velocityThresholdPx =
                                    with(density) { BottomSheetVelocityThreshold.toPx() },
                                allowDismiss = false,
                                onDismiss = null,
                            )
                        } catch (_: CancellationException) {
                            return Velocity.Zero
                        }
                        available
                    } else {
                        Velocity.Zero
                    }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    isTopReached = false
                    return Velocity.Zero
                }
            }
}

const val EXPANDED_ANCHOR = 2
const val COLLAPSED_ANCHOR = 1
const val DISMISSED_ANCHOR = 0

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = DISMISSED_ANCHOR,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by
        rememberSaveable {
            androidx.compose.runtime.mutableIntStateOf(initialAnchor)
        }

    val hasDismissedAnchor = !isAtSheetAnchor(dismissedBound, collapsedBound)
    val initialValue = legacyAnchor(previousAnchor, hasDismissedAnchor)
    val initialAnchors =
        remember(density, dismissedBound, collapsedBound, expandedBound) {
            buildBottomSheetAnchors(
                density = density,
                dismissedBound = dismissedBound,
                collapsedBound = collapsedBound,
                expandedBound = expandedBound,
            )
        }

    val anchoredState =
        remember(density) {
            AnchoredDraggableState(
                initialValue = initialValue,
                anchors = initialAnchors,
            )
        }

    val state =
        remember(anchoredState, coroutineScope, density) {
            BottomSheetState(
                coroutineScope = coroutineScope,
                anchoredState = anchoredState,
                density = density,
                onAnchorChanged = { previousAnchor = it },
                dismissedBound = dismissedBound,
                collapsedBound = collapsedBound,
                expandedBound = expandedBound,
            )
        }

    SideEffect {
        state.updateBounds(
            newDismissedBound = dismissedBound,
            newCollapsedBound = collapsedBound,
            newExpandedBound = expandedBound,
        )
    }

    return state
}

/**
 * Destructive swipe-down belongs to the compact interaction state, not to an arbitrary numeric
 * distance from the dock. Insets/navigation can move the collapsed anchor while the Mini still
 * looks and behaves fully docked; using rawProgress alone made that real-device state unable to
 * move downward even though synthetic tests started at exact zero.
 */
internal fun canStartCompactDismissGesture(
    rawProgress: Float,
    isDismissed: Boolean,
    targetAnchor: Int,
): Boolean =
    !isDismissed &&
        targetAnchor == COLLAPSED_ANCHOR &&
        miniPlayerForegroundCanAcceptInput(rawProgress, isDismissed = false)

/**
 * Vertical gesture owner for the entire sheet.
 *
 * Mini's horizontal track swipe remains a child gesture. We wait for vertical touch slop and only
 * consume after the axis is known; if a child has already consumed the stream for a horizontal
 * swipe/control interaction, this handler never takes ownership.
 */
private class BottomSheetGesturePolicy {
    var allowDismiss: Boolean = false
}

/**
 * One vertical gesture owner for the player sheet.
 *
 * Foundation's vertical draggable waits for vertical touch slop. Mini Player's horizontal detector
 * waits for horizontal touch slop, while child clickables keep their tap stream until an axis wins.
 * The full vertical drag is one AnchoredDraggable mutation, so a new drag interrupts a settle at
 * the exact current offset instead of racing a queue of snap coroutines.
 */
@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
    dismissOnlyFromCollapsed: Boolean = false,
): Modifier {
    val density = LocalDensity.current
    val velocityThresholdPx = with(density) { BottomSheetVelocityThreshold.toPx() }
    val gesturePolicy = remember(state) { BottomSheetGesturePolicy() }

    val verticalDragState =
        remember(state, gesturePolicy) {
            object : DraggableState {
                override suspend fun drag(
                    dragPriority: MutatePriority,
                    block: suspend DragScope.() -> Unit,
                ) {
                    state.dragUserInput(
                        allowDismiss = gesturePolicy.allowDismiss,
                        dragPriority = dragPriority,
                    ) {
                        val sheetScope = this
                        val pointerScope =
                            object : DragScope {
                                override fun dragBy(pixels: Float) {
                                    // Pointer Y grows downward; sheet offset grows toward Expanded.
                                    sheetScope.dragBy(-pixels)
                                }
                            }
                        block.invoke(pointerScope)
                    }
                }

                override fun dispatchRawDelta(delta: Float) {
                    state.dispatchRawUserDelta(
                        deltaPx = -delta,
                        allowDismiss = gesturePolicy.allowDismiss,
                    )
                }
            }
        }

    return draggable(
        state = verticalDragState,
        orientation = Orientation.Vertical,
        enabled = true,
        // Child controls own taps. Vertical drag takes ownership only after touch slop, then
        // AnchoredDraggable cancels the running settle at its current physical offset.
        startDragImmediately = false,
        onDragStarted = {
            gesturePolicy.allowDismiss =
                onDismiss != null &&
                    (
                        !dismissOnlyFromCollapsed ||
                            canStartCompactDismissGesture(
                                rawProgress = state.rawProgress,
                                isDismissed = state.isDismissed,
                                targetAnchor = state.targetAnchor,
                            )
                    )
        },
        onDragStopped = { pointerVelocity ->
            val allowDismissForGesture = gesturePolicy.allowDismiss
            gesturePolicy.allowDismiss = false
            try {
                state.settleUserInput(
                    velocity = -pointerVelocity,
                    velocityThresholdPx = velocityThresholdPx,
                    allowDismiss = allowDismissForGesture,
                    onDismiss = if (allowDismissForGesture) onDismiss else null,
                )
            } catch (_: CancellationException) {
                // A newer touch or authoritative transition owns the same anchored offset.
            }
        },
    )
}
