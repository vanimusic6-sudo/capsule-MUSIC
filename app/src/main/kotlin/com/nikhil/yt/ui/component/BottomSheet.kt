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
import androidx.compose.runtime.Stable
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

/**
 * A single physical Capsule sheet.
 *
 * Animated value/velocity reads deliberately live in layout/layer lambdas. That lets Compose
 * invalidate only position or the GPU layer on each frame instead of recomposing the whole player.
 * The mini-player stays mounted behind the full surface, preserving icon state and instant reversal.
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
    val canReopen by
        remember(state) {
            derivedStateOf {
                state.progress.coerceIn(0f, 1f) < 0.42f &&
                    !state.isDismissed
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
                    val topCornerRadius = 22.dp * (1f - motionProgress)
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

        /*
         * The mini-player is always alive while playback exists. Per-frame docking deformation is
         * evaluated directly by the layer, so the mini-player subtree does not recompose just because
         * the spring velocity changed.
         */
        if (onDismiss == null || !state.isDismissed) {
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
                            val rawProgress = state.progress
                            val motionProgress = rawProgress.coerceIn(0f, 1f)
                            val closingVelocity =
                                (-state.animationVelocity.value).coerceAtLeast(0f)
                            val closingVelocityWeight =
                                (closingVelocity / 980f).coerceIn(0f, 1f)
                            val closingDockWeight =
                                ((0.40f - motionProgress) / 0.40f).coerceIn(0f, 1f)
                            val collapseOvershootWeight =
                                ((-rawProgress) / 0.060f).coerceIn(0f, 1f)
                            val rawImpact =
                                maxOf(
                                    closingVelocityWeight * closingDockWeight,
                                    collapseOvershootWeight,
                                )
                            val impact = rawImpact * rawImpact * (3f - 2f * rawImpact)

                            scaleX = 1f + 0.0038f * impact
                            scaleY = 1f - 0.0062f * impact
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .clickable(
                            enabled = canReopen,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = state::expandSoft,
                        )
                        .fillMaxWidth()
                        .height(state.collapsedBound),
                content = collapsedContent,
            )
        }

        /*
         * Full content is not kept alive at rest while collapsed, so animated backgrounds and player
         * internals still cost nothing there. During travel only offset/layer properties are updated.
         */
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
                            val motionProgress = state.progress.coerceIn(0f, 1f)
                            val openingVelocity =
                                state.animationVelocity.value.coerceAtLeast(0f)
                            val openingVelocityWeight =
                                (openingVelocity / 1080f).coerceIn(0f, 1f)
                            val openingDockWeight =
                                ((motionProgress - 0.58f) / 0.42f).coerceIn(0f, 1f)
                            val rawImpact = openingVelocityWeight * openingDockWeight
                            val impact = rawImpact * rawImpact * (3f - 2f * rawImpact)

                            scaleX = 1f - 0.00105f * impact
                            scaleY = 1f + 0.00275f * impact
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

    val animationVelocity: Dp
        get() = animatable.velocity

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value == animatable.upperBound
    }

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
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