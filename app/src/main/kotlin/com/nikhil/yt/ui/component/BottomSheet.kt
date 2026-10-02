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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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
 * The visible handoff between the compact and full player occupies only the first third of the
 * sheet travel. The important part is that neither surface invents a second trajectory: both ride
 * the same BottomSheet, so the mini-player literally becomes the leading edge of the opening page.
 */
internal const val PlayerMorphHandoffWindow = 0.32f

/**
 * 0 while docked, 1 once the full player has visually taken over. This is deliberately pure and
 * bounded: the mini uses 1 - this value and the full player uses this value, so their opacity never
 * leaves a hole during a fast reverse gesture.
 */
internal fun playerMorphHandoff(progress: Float): Float {
    if (!progress.isFinite()) return 1f
    return CapsuleMotion.smooth(
        (progress.coerceIn(0f, 1f) / PlayerMorphHandoffWindow).coerceIn(0f, 1f),
    )
}

/** Capsule navigation and the moving player sheet share the same upper-corner language. */
internal val PlayerFrameCornerRadius = 26.dp

/**
 * A single physical Capsule sheet.
 *
 * Animated values are consumed from layout/layer lambdas so a drag invalidates position or the GPU
 * layer rather than recomposing the whole player tree.
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    gesturesEnabled: Boolean = true,
    allowSwipeDismiss: Boolean = true,
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
                (onDismiss == null || !state.isDismissed) && state.progress < 0.46f
            }
        }
    val miniBackgroundMotionEnabled by
        remember(state) {
            derivedStateOf {
                miniPlayerClockShouldRun(state.isExpanded, state.isDismissed) &&
                    state.rawProgress < PlayerMorphHandoffWindow
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
                    if (gesturesEnabled) Modifier.bottomSheetDraggable(
                        state, if (allowSwipeDismiss) onDismiss else null,
                    ) else Modifier,
                ),
    ) {
        if (gesturesEnabled && !state.isCollapsed && !state.isDismissed) {
            BackHandler(onBack = state::collapseSoft)
        }

        if (shouldComposeMini) {
            Box(
                modifier =
                    Modifier
                        /*
                         * Do not counter-translate the mini-player. It now rides the very same
                         * BottomSheet that becomes the full player, which is what creates the
                         * ArchiveTune-style "lift and flow" instead of a dock handoff between two
                         * unrelated objects. Only opacity changes during the first third of travel.
                         */
                        .graphicsLayer {
                            alpha = 1f - playerMorphHandoff(state.rawProgress)
                        }
                        .clickable(
                            enabled = canReopen && gesturesEnabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = state::expandSoft,
                        )
                        .fillMaxWidth()
                        .height(state.collapsedBound),
            ) {
                CompositionLocalProvider(
                    LocalCapsuleBackgroundMotionEnabled provides miniBackgroundMotionEnabled,
                ) {
                    collapsedContent()
                }
            }
        }

        if (!state.isCollapsed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        /*
                         * The outer BottomSheet already supplies the only translation we need. The
                         * full player therefore appears inside the exact surface the mini-player is
                         * riding instead of adding a second offset/scale/descent on top of it.
                         */
                        .graphicsLayer {
                            val raw = state.rawProgress.coerceIn(0f, 1f)
                            alpha = playerMorphHandoff(raw)

                            val topCornerRadius =
                                PlayerFrameCornerRadius * (1f - CapsuleMotion.smooth(raw))
                            shape =
                                RoundedCornerShape(
                                    topStart = topCornerRadius,
                                    topEnd = topCornerRadius,
                                )
                            clip = topCornerRadius > 0.dp
                        }
                        .background(backgroundColor),
                content = content,
            )
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
) : DraggableState by draggableState {
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
        onAnchorChanged(COLLAPSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(EXPANDED_ANCHOR)
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
        onAnchorChanged(DISMISSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.lowerBound!!, BottomSheetAnimationSpec)
        }
    }

    fun snapTo(value: Dp) {
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

    return remember(
        dismissedBound,
        expandedBound,
        collapsedBound,
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
        )
    }
}

@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
): Modifier =
    pointerInput(state, onDismiss) {
        val velocityTracker = VelocityTracker()

        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                velocityTracker.addPointerInputChange(change)
                state.dispatchRawDelta(
                    constrainBottomSheetDragDelta(
                        valuePx = state.value.toPx(),
                        collapsedPx = state.collapsedBound.toPx(),
                        deltaPx = dragAmount,
                        allowDismiss = onDismiss != null,
                    ),
                )
            },
            onDragCancel = {
                velocityTracker.resetTracking()
                state.settle(onDismiss)
            },
            onDragEnd = {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, onDismiss)
            },
        )
    }

/** A non-dismissable sheet can reach its dock, but a drag cannot carry it below that dock. */
internal fun constrainBottomSheetDragDelta(
    valuePx: Float,
    collapsedPx: Float,
    deltaPx: Float,
    allowDismiss: Boolean,
): Float = if (allowDismiss || deltaPx <= 0f) deltaPx
    else deltaPx.coerceAtMost((valuePx - collapsedPx).coerceAtLeast(0f))
