/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nikhil.yt.constants.BottomSheetAnimationSpec
import com.nikhil.yt.constants.BottomSheetCollapseAnimationSpec
import com.nikhil.yt.constants.BottomSheetSoftAnimationSpec
import com.nikhil.yt.constants.BottomSheetSoftCollapseAnimationSpec
import com.nikhil.yt.ui.motion.CapsuleMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

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
 * Mini Player and full Player are deliberately separate visual surfaces.
 *
 * They share only this transition progress. The compact card stays physically pinned to its dock
 * while the full player travels independently from below the viewport. This keeps Mini controls,
 * track swipes and dismissal ownership independent from the full player's composition/lifecycle.
 */
internal const val MiniSurfaceFadeStart = 0.08f
internal const val MiniSurfaceFadeEnd = 0.30f

internal fun miniPlayerSurfaceAlpha(progress: Float): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    val span = (MiniSurfaceFadeEnd - MiniSurfaceFadeStart).coerceAtLeast(0.0001f)
    return (
        1f -
            CapsuleMotion.smooth(
                ((p - MiniSurfaceFadeStart) / span).coerceIn(0f, 1f),
            )
        ).coerceIn(0f, 1f)
}

/** Counter-translation that keeps Mini Player fixed at the dock while Player travels upward. */
internal fun miniPlayerPinOffset(
    value: Dp,
    collapsedBound: Dp,
): Dp = (value - collapsedBound).coerceAtLeast(0.dp)

/** Full Player starts below the viewport and independently loses this offset as it opens. */
internal fun fullPlayerRevealOffset(
    collapsedBound: Dp,
    progress: Float,
): Dp {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return collapsedBound * (1f - p)
}

/** Capsule navigation and the moving player sheet share the same upper-corner language. */
internal val PlayerFrameCornerRadius = 26.dp

/**
 * Safe rounded edge for the moving player frame.
 *
 * This is intentionally a geometry boundary rather than an inline expression. Even a legal 0..1
 * easing can land one floating-point ULP past an endpoint on a device/JIT combination. Compose
 * rejects negative corner sizes, so clamp both the eased fraction and the final Dp defensively.
 */
internal fun playerFrameCornerRadius(progress: Float): Dp {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    val eased = CapsuleMotion.smooth(p).coerceIn(0f, 1f)
    return (PlayerFrameCornerRadius * (1f - eased)).coerceAtLeast(0.dp)
}

/** Below this fraction the full surface is visually indistinguishable from the collapsed anchor. */
internal const val SheetExpandedRenderFloor = 0.025f

internal fun shouldRenderExpandedSurface(rawProgress: Float, targetAnchor: Int): Boolean {
    if (targetAnchor == DISMISSED_ANCHOR) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    // Never mount a full-screen hit surface while the sheet is still physically at the compact
    // anchor. This keeps the mini tappable even if an expand coroutine is cancelled before its
    // first movement frame. Once travel actually starts, targetAnchor still owns the lifecycle.
    return p > SheetExpandedRenderFloor
}

internal fun shouldShowCompactSurface(rawProgress: Float, targetAnchor: Int): Boolean {
    if (targetAnchor == DISMISSED_ANCHOR) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 1f
    return p < MiniSurfaceFadeEnd
}

/**
 * Foreground and physical-surface motion are deliberately different.
 *
 * Mini opening holds its content for a short beat, then releases it while the compact shell keeps
 * travelling. Full-player opening does NOT have a shell-only stage: its UI rides in with the
 * surface. The shell-only moment exists only on close, very late in the return trip.
 */
internal const val MiniPlayerForegroundFadeStart = 0.06f
internal const val MiniPlayerForegroundFadeEnd = 0.20f
internal const val FullPlayerCloseForegroundFadeStart = 0.22f
internal const val FullPlayerCloseForegroundFadeEnd = 0.08f

internal fun miniPlayerForegroundAlpha(rawProgress: Float): Float {
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 1f
    val span = (MiniPlayerForegroundFadeEnd - MiniPlayerForegroundFadeStart)
        .coerceAtLeast(0.0001f)
    val t = ((p - MiniPlayerForegroundFadeStart) / span).coerceIn(0f, 1f)
    return (1f - CapsuleMotion.smooth(t)).coerceIn(0f, 1f)
}

internal fun fullPlayerForegroundAlpha(
    rawProgress: Float,
    targetAnchor: Int,
): Float {
    if (targetAnchor == EXPANDED_ANCHOR) return 1f
    if (targetAnchor == DISMISSED_ANCHOR) return 0f

    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    val span = (FullPlayerCloseForegroundFadeStart - FullPlayerCloseForegroundFadeEnd)
        .coerceAtLeast(0.0001f)
    return CapsuleMotion.smooth(
        ((p - FullPlayerCloseForegroundFadeEnd) / span).coerceIn(0f, 1f),
    ).coerceIn(0f, 1f)
}

internal const val MiniGestureCoordinatorProgressCeiling = MiniPlayerForegroundFadeEnd

internal const val MiniOpenCommitProgress = 0.12f
internal const val MiniOpenFlingVelocity = 420f
internal const val MiniOpenReverseVelocity = -220f

internal fun shouldExpandMiniGesture(
    rawProgress: Float,
    velocity: Float,
): Boolean {
    if (velocity >= MiniOpenFlingVelocity) return true
    if (velocity <= MiniOpenReverseVelocity) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    return p >= MiniOpenCommitProgress
}

internal fun canStartMiniGestureCoordinator(rawProgress: Float): Boolean {
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 1f
    // The compact surface owns input for as long as its foreground is still visibly present.
    // This is deliberately wider than the destructive dismiss window below: horizontal track
    // swipes and taps must not disappear just because the sheet moved a few percent off its dock.
    return p <= MiniGestureCoordinatorProgressCeiling
}

internal fun miniPlayerForegroundCanAcceptInput(
    rawProgress: Float,
    targetAnchor: Int,
): Boolean {
    if (targetAnchor == DISMISSED_ANCHOR) return false
    return canStartMiniGestureCoordinator(rawProgress)
}

/**
 * Full controls become interactive once enough of the full surface is physically present.
 * Keep this independent from the late close-only visual fade.
 */
internal const val PlayerExpandedInputFloor = 0.28f

internal fun expandedPlayerCanAcceptInput(rawProgress: Float, targetAnchor: Int): Boolean {
    if (targetAnchor == DISMISSED_ANCHOR) return false
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    return p > PlayerExpandedInputFloor
}

/**
 * Shared motion controller for two independent surfaces.
 *
 * The outer state owns only the open/close progress. Mini Player is counter-translated to remain
 * docked; full Player has its own reveal offset. Animated values stay in layout/layer lambdas so a
 * drag invalidates position or the GPU layer rather than recomposing either player tree.
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
    expandedContentInteractive: Boolean = true,
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
                    state.rawProgress < MiniSurfaceFadeEnd
            }
        }
    val renderExpandedSurface by
        remember(state) {
            derivedStateOf {
                shouldRenderExpandedSurface(state.rawProgress, state.targetAnchor)
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
                        // Counter the parent sheet's upward travel: Mini Player remains a docked,
                        // independent surface instead of being physically dragged into Player.
                        .offset {
                            IntOffset(
                                x = 0,
                                y =
                                    miniPlayerPinOffset(
                                        value = state.value,
                                        collapsedBound = state.collapsedBound,
                                    ).roundToPx(),
                            )
                        }
                        .graphicsLayer {
                            alpha = miniPlayerSurfaceAlpha(state.rawProgress)
                        }
                        // Keep the fading Mini above the arriving Player, but input is separately
                        // gated and turns off before the visual surface is completely gone.
                        .zIndex(
                            if (state.rawProgress < MiniSurfaceFadeEnd) 2f else 0f,
                        )
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
                        // Full Player is its own travelling canvas. At the compact anchor its top
                        // edge is below the viewport; opening removes this offset independently of
                        // the Mini Player, which remains pinned to the dock above it.
                        .offset {
                            IntOffset(
                                x = 0,
                                y =
                                    fullPlayerRevealOffset(
                                        collapsedBound = state.collapsedBound,
                                        progress = state.rawProgress,
                                    ).roundToPx(),
                            )
                        }
                        .graphicsLayer {
                            val raw = state.rawProgress.coerceIn(0f, 1f)
                            val topCornerRadius = playerFrameCornerRadius(raw)
                            shape =
                                RoundedCornerShape(
                                    topStart = topCornerRadius,
                                    topEnd = topCornerRadius,
                                )
                            clip = topCornerRadius > 0.dp
                        }
                        .background(backgroundColor),
            ) {
                content()

                if (!expandedContentInteractive) {
                    /*
                     * Visuals are allowed to finish travelling underneath navigation, but invisible
                     * player controls must never remain a hit target. This sibling shield wins hit
                     * testing over the player content while leaving the parent sheet's drag
                     * detector in the pointer path, so swipe/reverse gestures still work.
                     */
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .zIndex(1_000f)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent()
                                        }
                                    }
                                },
                    )
                }
            }
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    collapsedBound: Dp,
    initialAnchor: Int,
) : DraggableState by draggableState {
    private val collapsedBoundState = mutableStateOf(collapsedBound)

    val collapsedBound: Dp
        get() = collapsedBoundState.value

    var targetAnchor by mutableIntStateOf(initialAnchor)
        private set

    private var lastAnimationSpec: AnimationSpec<Dp> = BottomSheetAnimationSpec

    val isExpandedOrExpanding: Boolean
        get() = targetAnchor == EXPANDED_ANCHOR

    val isCollapsedOrCollapsing: Boolean
        get() = targetAnchor == COLLAPSED_ANCHOR

    val isDismissedOrDismissing: Boolean
        get() = targetAnchor == DISMISSED_ANCHOR

    val shouldLayerAboveCollapsedChrome: Boolean
        get() = shouldRenderExpandedSurface(rawProgress, targetAnchor)

    val compactSurfaceVisible: Boolean
        get() = shouldShowCompactSurface(rawProgress, targetAnchor)

    val compactForegroundAcceptsInput by
        derivedStateOf {
            miniPlayerForegroundCanAcceptInput(rawProgress, targetAnchor)
        }

    val expandedSurfaceAcceptsInput by
        derivedStateOf {
            expandedPlayerCanAcceptInput(rawProgress, targetAnchor)
        }

    private fun updateAnchor(anchor: Int) {
        targetAnchor = anchor
        onAnchorChanged(anchor)
    }

    internal fun updateCollapsedBound(newBound: Dp) {
        val clamped = newBound.coerceIn(animatable.lowerBound!!, animatable.upperBound!!)
        val previous = collapsedBoundState.value
        if (clamped == previous) return

        val wasRestingAtCollapsed =
            !animatable.isRunning &&
                (animatable.value - previous).value.absoluteValue <= 0.5f
        collapsedBoundState.value = clamped

        if (targetAnchor == COLLAPSED_ANCHOR) {
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (wasRestingAtCollapsed) {
                    animatable.snapTo(clamped)
                } else {
                    animatable.animateTo(clamped, lastAnimationSpec)
                }
            }
        }
    }
    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isDismissed by
        derivedStateOf {
            isAtSheetAnchor(value, animatable.lowerBound!!)
        }

    val isCollapsed by
        derivedStateOf {
            isAtSheetAnchor(value, collapsedBound)
        }

    val isExpanded by
        derivedStateOf {
            isAtSheetAnchor(value, animatable.upperBound!!)
        }

    val rawProgress by
        derivedStateOf {
            val range = animatable.upperBound!! - collapsedBound
            if (range == 0.dp) {
                0f
            } else {
                1f - (animatable.upperBound!! - animatable.value) / range
            }
        }

    /** Quintic smootherstep: zero velocity and acceleration at both visual anchors. */
    val progress by
        derivedStateOf {
            val p = rawProgress.coerceIn(0f, 1f)
            val smooth = p * p * p * (p * (p * 6f - 15f) + 10f)
            smooth.coerceIn(0f, 1f)
        }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(COLLAPSED_ANCHOR)
        lastAnimationSpec = animationSpec
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(EXPANDED_ANCHOR)
        lastAnimationSpec = animationSpec
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    private fun collapse() {
        collapse(
            if (collapsedBound == dismissedBound) {
                BottomSheetAnimationSpec
            } else {
                BottomSheetCollapseAnimationSpec
            },
        )
    }

    private fun expand() {
        expand(BottomSheetAnimationSpec)
    }

    fun collapseSoft() {
        collapse(
            if (collapsedBound == dismissedBound) {
                BottomSheetSoftAnimationSpec
            } else {
                BottomSheetSoftCollapseAnimationSpec
            },
        )
    }

    fun expandSoft() {
        expand(BottomSheetSoftAnimationSpec)
    }

    fun dismiss() {
        updateAnchor(DISMISSED_ANCHOR)
        lastAnimationSpec = BottomSheetAnimationSpec
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.lowerBound!!, BottomSheetAnimationSpec)
        }
    }

    fun snapTo(value: Dp) {
        updateAnchor(
            when {
                isAtSheetAnchor(value, expandedBound) -> EXPANDED_ANCHOR
                isAtSheetAnchor(value, collapsedBound) -> COLLAPSED_ANCHOR
                isAtSheetAnchor(value, dismissedBound) -> DISMISSED_ANCHOR
                else -> COLLAPSED_ANCHOR
            },
        )
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.snapTo(value)
        }
    }

    fun settle(onDismiss: (() -> Unit)? = null) {
        performFling(velocity = 0f, onDismiss = onDismiss)
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        val flingThreshold = 900f

        if (velocity > flingThreshold) {
            expand()
            return
        }

        if (velocity < -flingThreshold) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
            return
        }

        val dismissMidpoint =
            dismissedBound + (collapsedBound - dismissedBound) / 2f
        val expandMidpoint =
            collapsedBound + (expandedBound - collapsedBound) / 2f

        when {
            value < dismissMidpoint && onDismiss != null -> {
                dismiss()
                onDismiss.invoke()
            }

            value < expandMidpoint -> collapse()
            else -> expand()
        }
    }

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
                        dispatchRawDelta(available.y)
                        available
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
                        dispatchRawDelta(available.y)
                        available
                    } else {
                        Offset.Zero
                    }
                }

                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (isTopReached) {
                        val velocity = -available.y
                        performFling(velocity, null)
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
            mutableIntStateOf(initialAnchor)
        }
    val animatable =
        remember {
            Animatable(0.dp, Dp.VectorConverter)
        }

    val state = remember(
        dismissedBound,
        expandedBound,
        coroutineScope,
    ) {
        val initialValue =
            when (previousAnchor) {
                EXPANDED_ANCHOR -> expandedBound
                COLLAPSED_ANCHOR -> collapsedBound
                DISMISSED_ANCHOR -> dismissedBound
                else -> error("Unknown BottomSheet anchor")
            }

        animatable.updateBounds(
            dismissedBound.coerceAtMost(expandedBound),
            expandedBound,
        )
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.snapTo(initialValue)
        }

        BottomSheetState(
            draggableState =
                DraggableState { delta ->
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.snapTo(
                            animatable.value - with(density) { delta.toDp() },
                        )
                    }
                },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
            initialAnchor = previousAnchor,
        )
    }

    LaunchedEffect(state, collapsedBound) {
        state.updateCollapsedBound(collapsedBound)
    }

    return state
}

internal const val MiniDismissStartProgressCeiling = 0.03f

internal fun canStartMiniDismissGesture(rawProgress: Float): Boolean {
    val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 1f
    return p <= MiniDismissStartProgressCeiling
}

@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
    dismissOnlyFromCollapsed: Boolean = false,
): Modifier =
    pointerInput(state, onDismiss, dismissOnlyFromCollapsed) {
        /*
         * Gesture ownership is decided once, on DOWN.
         *
         * A physically docked compact player owns its complete pointer stream. Its coordinator
         * decides horizontal track swipe vs vertical sheet drag after one shared touch-slop gate.
         * The parent sheet deliberately observes that gesture without consuming it. This removes
         * the old race between detectVerticalDragGestures here and detectHorizontalDragGestures in
         * CapsuleMiniPlayer.
         *
         * Once the player is away from the compact dock, the sheet owns vertical dragging as
         * before. Ownership never changes half-way through one pointer sequence.
         */
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (canStartMiniGestureCoordinator(state.rawProgress)) {
                waitForUpOrCancellation()
                return@awaitEachGesture
            }

            val velocityTracker = VelocityTracker()
            velocityTracker.resetTracking()

            var accepted = false
            val dragStart =
                awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    if (!change.isConsumed) {
                        accepted = true
                        change.consume()
                        velocityTracker.addPointerInputChange(change)
                        state.dispatchRawDelta(
                            constrainBottomSheetDragDelta(
                                valuePx = state.value.toPx(),
                                collapsedPx = state.collapsedBound.toPx(),
                                deltaPx = overSlop,
                                allowDismiss = false,
                            ),
                        )
                    }
                }

            if (!accepted || dragStart == null) {
                velocityTracker.resetTracking()
                return@awaitEachGesture
            }

            val completed =
                verticalDrag(dragStart.id) { change ->
                    if (change.isConsumed) return@verticalDrag
                    velocityTracker.addPointerInputChange(change)
                    val dragAmount = change.positionChange().y
                    change.consume()
                    state.dispatchRawDelta(
                        constrainBottomSheetDragDelta(
                            valuePx = state.value.toPx(),
                            collapsedPx = state.collapsedBound.toPx(),
                            deltaPx = dragAmount,
                            allowDismiss = false,
                        ),
                    )
                }

            val velocity =
                if (completed) {
                    -velocityTracker.calculateVelocity().y
                } else {
                    0f
                }
            velocityTracker.resetTracking()
            state.performFling(velocity, null)
        }
    }

/** A non-dismissable sheet can reach its dock, but a drag cannot carry it below that dock. */
internal fun constrainBottomSheetDragDelta(
    valuePx: Float,
    collapsedPx: Float,
    deltaPx: Float,
    allowDismiss: Boolean,
): Float = if (allowDismiss || deltaPx <= 0f) deltaPx
    else deltaPx.coerceAtMost((valuePx - collapsedPx).coerceAtLeast(0f))
