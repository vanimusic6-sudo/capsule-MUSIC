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
import com.nikhil.yt.ui.motion.CapsuleMotion
import androidx.compose.ui.graphics.TransformOrigin
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
 * The last stretch of travel, where the player reads as folding into the mini-player: it shrinks
 * towards the dock and fades out, so the mini-player is what is left behind rather than something
 * that was underneath all along.
 */
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
internal fun isAtSheetAnchor(value: Dp, anchor: Dp): Boolean =
    (value - anchor).value.absoluteValue <= ANCHOR_EPSILON_DP

internal const val PlayerFoldWindow = 0.76f
private const val PlayerFoldScale = 0.05f

/**
 * How much of the close the dock takes to come up: all of it.
 *
 * The dock kept reading as late, and widening its window helped each time, so it is now as wide as
 * it can be — it begins the instant the player leaves the top of its travel and is complete at the
 * bottom. There is no earlier than this; the handover already spans the whole gesture.
 *
 * A window this wide is only usable because the curve is a smoothstep, which has zero slope at both
 * ends. The dock therefore does not blink into existence at the start of the close — it is
 * mathematically still invisible for the first frames and arrives without an edge — where a linear
 * ramp this wide would put a visible seam at the very moment the player starts moving.
 *
 * The two layers still cover each other. The dock's window is wider than the player's, so
 * `approach` is never smaller for the player than for the dock, which makes the two opacities sum to
 * at least one at every point in the travel: there is no instant where the wallpaper can show
 * between them.
 */
internal const val DockHandoverWindow = 1f


/*
 * The dock and the player hand over to each other; they do not animate independently.
 *
 * Earlier versions gave the dock motion of its own — a squash from closing velocity, a pull, an
 * arrival rock — and each read as twitching, because two separately animated surfaces can only
 * agree by coincidence. Then the dock was left completely static, which read as *late*: the player
 * faded to a low alpha while the dock stayed fully opaque underneath, so for most of the close the
 * two were simply stacked, and the dock only looked clean once the player had almost gone.
 *
 * Now both opacities are read off the same progress, so the exchange has no seam and neither side
 * can lag the other. They are not strict complements any more: the dock rises over the whole travel
 * while the player holds on over the last three quarters of it, which keeps the pair opaque (see
 * DockHandoverWindow) and means the dock is already coming up from the moment the player starts
 * down, rather than dropping in near the end.
 *
 * The dock is given opacity and nothing else. Every geometric treatment tried on it — a velocity
 * squash, a pull, an arrival rock, and finally growing a few percent into place — read as trembling,
 * and the last one explains the rest: transforming a layer full of text and artwork re-rasterises it
 * every frame, and that sub-pixel shimmer looks like shaking however smooth the underlying motion
 * is. A cross-fade cannot shimmer.
 *
 * Both sides are monotonic in the sheet's own progress, so neither can overshoot, reverse, or lag
 * the other, and both are exactly identity at the dock.
 */

/**
 * A single physical Capsule sheet.
 *
 * Animated value/velocity reads deliberately live in layout/layer lambdas. That lets Compose
 * invalidate only position or the GPU layer on each frame instead of recomposing the whole player.
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    /*
     * Keep the mini-player composition alive for the lifetime of an active queue. Recreating it
     * after every full-player close caused Room-backed subscribe state to bootstrap again and replay
     * the plus -> check morph even though the user had not subscribed again.
     */
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
                    state.progress < 0.46f
            }
        }
    val miniBackgroundMotionEnabled by
        remember(state) {
            derivedStateOf {
                // Keep the subtree and all Room/player state alive. Only the decorative clock sleeps
                // at the fully expanded anchor and wakes on the first closing/drag frame.
                !state.isExpanded
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
                .bottomSheetDraggable(state, onDismiss)
                .graphicsLayer {
                    val motionProgress = state.progress.coerceIn(0f, 1f)
                    // Springs and Float math can land a few ulps above 1f. Android 16 rejects even
                    // a microscopic negative corner radius, so geometry is clamped independently of
                    // the animation math as a final safety boundary.
                    val topCornerRadius =
                        (22.dp * (1f - motionProgress)).coerceAtLeast(0.dp)
                    shape =
                        RoundedCornerShape(
                            topStart = topCornerRadius,
                            topEnd = topCornerRadius,
                        )
                    clip = true
                },
    ) {
        if (!state.isCollapsed && !state.isDismissed) {
            BackHandler(onBack = state::collapseSoft)
        }

        if (shouldComposeMini) {
            Box(
                modifier =
                    Modifier
                        .offset {
                            val miniPinOffset =
                                (state.value - state.collapsedBound)
                                    .coerceAtLeast(0.dp)
                            IntOffset(
                                x = 0,
                                y = miniPinOffset.roundToPx(),
                            )
                        }
                        .graphicsLayer {
                            val fold =
                                CapsuleMotion.approach(
                                    progress = state.progress,
                                    window = DockHandoverWindow,
                                )

                            // Opacity only, over the whole travel, so the dock is already on its way
                            // in while the player is still near the top rather than appearing once
                            // the player has nearly gone.
                            //
                            // It used to grow the last few percent into place as well, and that is
                            // what read as trembling: scaling a layer full of text and artwork
                            // re-rasterises it every frame, and the sub-pixel shimmer that produces
                            // looks like the dock shaking even though it is moving perfectly
                            // smoothly. A pure cross-fade cannot shimmer.
                            alpha = (1f - fold).coerceIn(0f, 1f)
                        }
                        .clickable(
                            enabled = canReopen,
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
                        .offset {
                            val motionProgress = state.progress.coerceIn(0f, 1f)
                            val revealOffset =
                                state.collapsedBound *
                                    (1f - motionProgress)
                            IntOffset(
                                x = 0,
                                y = revealOffset.roundToPx(),
                            )
                        }
                        .graphicsLayer {
                            /*
                             * The player folds into the dock over the last of its travel: it scales
                             * down towards the mini-player and fades, so closing reads as the player
                             * going *into* it.
                             *
                             * This is the only thing that moves during a close. It reads the same
                             * progress that positions the sheet, so the fold and the travel are one
                             * motion by construction, and it reaches exactly identity at the moment
                             * the sheet reaches the dock.
                             *
                             * There is deliberately no blur here. A full-screen RenderEffect forces
                             * an offscreen buffer for the entire player every frame, and allocating
                             * it is what made the very first open stall. Scale and alpha are free by
                             * comparison — the layer already exists — and both are exactly identity
                             * once the player is open, so an open player costs nothing.
                             */
                            val fold =
                                CapsuleMotion.approach(
                                    progress = state.progress,
                                    window = PlayerFoldWindow,
                                )

                            val folded = 1f - fold
                            scaleX = 1f - PlayerFoldScale * folded
                            scaleY = 1f - PlayerFoldScale * folded
                            // Handed straight to the dock below, which takes 1 - fold. Reaching zero
                            // rather than stopping short is what removes the stacked-surfaces look
                            // that made the dock seem to arrive late.
                            alpha = fold.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0.5f, 1f)
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

    // Anchors are matched with a tolerance rather than by exact equality; see [isAtSheetAnchor].
    val isDismissed by derivedStateOf {
        isAtSheetAnchor(value, animatable.lowerBound!!)
    }

    val isCollapsed by derivedStateOf {
        isAtSheetAnchor(value, collapsedBound)
    }

    val isExpanded by derivedStateOf {
        isAtSheetAnchor(value, animatable.upperBound!!)
    }

    /**
     * Physical anchor progress, before easing. May sit slightly outside 0..1 while a spring settles,
     * which is why every consumer clamps; [progress] is the value surfaces should read.
     */
    val rawProgress by derivedStateOf {
        val range = animatable.upperBound!! - collapsedBound
        if (range == 0.dp) {
            0f
        } else {
            1f - (animatable.upperBound!! - animatable.value) / range
        }
    }

    /**
     * Visual docking progress. Quintic smootherstep reaches both anchors with zero velocity and
     * acceleration without creating a second animation engine inside BottomSheetState.
     */
    val progress by derivedStateOf {
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

        val dismissMidpoint = dismissedBound + (collapsedBound - dismissedBound) / 2f
        val expandMidpoint = collapsedBound + (expandedBound - collapsedBound) / 2f

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

                    return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
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

                    return if (isTopReached && source == NestedScrollSource.UserInput) {
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

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable =
        remember {
            Animatable(0.dp, Dp.VectorConverter)
        }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue =
            when (previousAnchor) {
                EXPANDED_ANCHOR -> expandedBound
                COLLAPSED_ANCHOR -> collapsedBound
                DISMISSED_ANCHOR -> dismissedBound
                else -> error("Unknown BottomSheet anchor")
            }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.snapTo(initialValue)
        }

        BottomSheetState(
            draggableState =
                DraggableState { delta ->
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.snapTo(animatable.value - with(density) { delta.toDp() })
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
    pointerInput(state) {
        val velocityTracker = VelocityTracker()

        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                velocityTracker.addPointerInputChange(change)
                state.dispatchRawDelta(dragAmount)
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
