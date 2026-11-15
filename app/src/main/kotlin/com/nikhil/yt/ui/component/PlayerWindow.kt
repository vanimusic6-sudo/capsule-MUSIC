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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex

/**
 * Only the window's outline changes aspect. The real page keeps one measurement and one uniform
 * scale. The real Mini rides the same upper edge and hands off directly to the real page. There
 * is no stretched palette panel, duplicated backdrop, screenshot or decoder. Generic/queue sheets
 * do not enter this implementation. Node lifetimes still span the complete finite motion.
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
    backgroundContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val arbiter = remember(state) { MiniPlayerGestureArbiter() }
    val renderPage by remember(state) { derivedStateOf {
        !state.isDismissed && (state.isExpandedOrExpanding ||
            (state.isCollapsedOrCollapsing && state.isAnimationRunning) ||
            shouldRenderExpandedSurface(state.visualProgress, state.isDismissed))
    } }
    val sourceDraws by remember(state) { derivedStateOf {
        playerMiniDrawAlpha(state.visualProgress) > 0f
    } }
    val dismissingMini by remember(state) { derivedStateOf { state.rawProgress < 0f } }
    val sourceClock by remember(state) { derivedStateOf {
        !state.isDismissed && sourceDraws
    } }
    val pageDraws by remember(state) { derivedStateOf { playerPageOpacity(state.visualProgress) > 0f } }
    val pageAccessible by remember(state) { derivedStateOf { playerPageOpacity(state.visualProgress) >= 1f } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(modifier.fillMaxSize().offset {
        IntOffset(0, if (dismissingMini) (state.collapsedBound - state.value).roundToPx() else 0)
    }) {
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier
                    .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                    .zIndex(if (sourceDraws) 2f else 0f)
                    .semantics {
                        if (!state.compactForegroundAcceptsInput) hideFromAccessibility()
                    }
                    .graphicsLayer {
                        val dock = (state.expandedBound - state.collapsedBound).toPx()
                        translationY = playerMiniTop(state.visualProgress,
                            state.expandedBound.toPx(), state.collapsedBound.toPx()) - dock
                        alpha = playerMiniDrawAlpha(state.visualProgress)
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
                        // Clip the real page. No extra material is painted into the growing frame.
                        alpha = 1f
                    },
            ) {
                // Keep the destination attached until the motion settles, including transparent
                // closing frames. The real Mini is the only source; no second source node exists.
                key("player-destination-page") {
                Box(Modifier.fillMaxSize().semantics {
                    if (!pageAccessible) hideFromAccessibility()
                }.graphicsLayer {
                    val p = state.visualProgress
                    val frame = playerWindowBounds(p, size, miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                    val scale = playerPageScale(p, frame.width / size.width.coerceAtLeast(1f))
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
}
