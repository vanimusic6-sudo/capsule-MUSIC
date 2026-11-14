/* Capsule MUSIC — a material container transform, not a sliding page crop. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex

/**
 * Only the window's outline changes aspect. The real page keeps one measurement and one uniform
 * scale. Its background and controls dissolve together INSIDE the source material, never over the
 * app. A small source-material layer is stretched on the GPU; no screenshot, bitmap or decoder is
 * created for this transition. Generic/queue sheets do not enter this implementation.
 */
@Composable
internal fun PlayerWindow(
    state: BottomSheetState,
    modifier: Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)?,
    compactSwipeDismissEnabled: Boolean,
    backHandlerEnabled: Boolean,
    miniHeight: Dp,
    miniInset: Dp,
    miniRadius: Dp,
    collapsedContent: @Composable BoxScope.() -> Unit,
    compactBackground: (@Composable BoxScope.() -> Unit)?,
    backgroundContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val arbiter = remember(state) { MiniPlayerGestureArbiter() }
    val renderPage by remember(state) { derivedStateOf {
        shouldRenderExpandedSurface(state.visualProgress, state.isDismissed)
    } }
    val renderSource by remember(state) { derivedStateOf {
        renderPage && playerPageOpacity(state.visualProgress) < 1f
    } }
    val sourceDraws by remember(state) { derivedStateOf {
        playerMiniDrawAlpha(state.visualProgress) > 0f
    } }
    val dismissingMini by remember(state) { derivedStateOf { state.rawProgress < 0f } }
    val sourceClock by remember(state) { derivedStateOf {
        !state.isDismissed && sourceDraws
    } }
    val pageDraws by remember(state) { derivedStateOf { playerPageOpacity(state.visualProgress) > 0f } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(modifier.fillMaxSize().offset {
        IntOffset(0, if (dismissingMini) (state.collapsedBound - state.value).roundToPx() else 0)
    }) {
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier
                    .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                    .zIndex(if (sourceDraws) 2f else 0f)
                    .graphicsLayer {
                        alpha = playerMiniDrawAlpha(state.visualProgress)
                        scaleY = 1f + playerMiniStretch(state.visualProgress, state.frameVelocityFraction)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .then(if (compactSwipeDismissEnabled && onDismiss != null) Modifier.bottomSheetDraggable(
                        state = state, onDismiss = onDismiss, dismissOnlyFromCollapsed = true,
                        miniGestureArbiter = arbiter, compactDismissOnly = true,
                    ) else Modifier)
                    .clickable(
                        enabled = state.compactForegroundAcceptsInput,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::expandSoft,
                    )
                    .fillMaxWidth().height(miniHeight),
            ) {
                CompositionLocalProvider(
                    LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                    LocalMiniPlayerGestureArbiter provides arbiter,
                ) { collapsedContent() }
            }
        }

        if (renderPage) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (playerPageOpacity(state.visualProgress) < 1f) {
                                down.consume()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }
                    .graphicsLayer {
                        val p = state.visualProgress
                        val frame = playerWindowBounds(p, size, miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                        val contour = playerFrameContour(p, miniInset.toPx(), miniRadius.toPx(), density)
                        shape = PlayerWindowShape(frame, contour.radius, contour.bleed)
                        clip = p < 1f
                        // The shell is always whole; only the page INSIDE it changes opacity.
                        alpha = 1f
                    },
            ) {
                if (renderSource) {
                    Box(Modifier.fillMaxWidth().height(miniHeight).graphicsLayer {
                        val frame = playerWindowBounds(state.visualProgress,
                            androidx.compose.ui.geometry.Size(size.width, state.expandedBound.toPx()),
                            miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = frame.left
                        translationY = frame.top
                        scaleX = frame.width / size.width.coerceAtLeast(1f)
                        scaleY = frame.height / size.height.coerceAtLeast(1f)
                    }) {
                        CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides false) {
                            if (compactBackground != null) compactBackground()
                            else Box(Modifier.fillMaxSize().background(backgroundColor))
                        }
                    }
                }

                Box(Modifier.fillMaxSize().graphicsLayer {
                    val p = state.visualProgress
                    val frame = playerWindowBounds(p, size, miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                    val scale = playerPageScale(p)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = (size.width - size.width * scale) / 2f
                    translationY = frame.center.y - size.height * scale / 2f
                    alpha = playerPageOpacity(p)
                }.drawWithContent {
                    if (playerPageOpacity(state.visualProgress) > 0f) drawContent()
                }) {
                    CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides pageDraws) {
                    Box(Modifier.fillMaxSize().background(backgroundColor)
                        .pointerInput(renderPage) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }, content = backgroundContent)
                    content()
                    }
                }
            }
        }
    }
}
