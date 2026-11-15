/* Capsule MUSIC — one opaque, top-anchored player surface. GPL-3.0. */
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex

/**
 * The real, full-size page moves as one opaque surface from the Mini dock to the screen top.
 * Source controls follow the same top edge and retire over that surface. There is no growing
 * crop window, page recentering, zoom, palette bridge or translucent destination.
 * Keep layout and semantics nodes attached until the finite motion actually settles.
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
    val pageDraws by remember(state) { derivedStateOf { playerPageVisible(state.visualProgress) } }
    val pageAccessible by remember(state) { derivedStateOf { playerPageAcceptsInput(state.visualProgress) } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(modifier.fillMaxSize().offset {
        IntOffset(0, if (dismissingMini) (state.collapsedBound - state.value).roundToPx() else 0)
    }) {
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier
                    .offset {
                        // Place the whole Mini layer, including its alpha buffer. Translating a
                        // child inside the fixed dock's placement layer clips that buffer to a strip.
                        IntOffset(0, with(this) {
                            playerSurfaceTop(state.visualProgress, state.expandedBound.toPx(),
                                state.collapsedBound.toPx()).toDp().roundToPx()
                        })
                    }
                    .zIndex(if (sourceDraws) 2f else 0f)
                    .semantics {
                        if (!state.compactForegroundAcceptsInput) hideFromAccessibility()
                    }
                    .graphicsLayer {
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
            // One layer owns translation and the native outline. The page is measured once at
            // its final size, with its top anchored to the source rather than centred in a mask.
            Box(
                Modifier.fillMaxSize()
                    .semantics { if (!pageAccessible) hideFromAccessibility() }
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (!playerPageAcceptsInput(state.visualProgress)) {
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
                        translationY = playerSurfaceTop(p, state.expandedBound.toPx(), state.collapsedBound.toPx())
                        val contour = playerPageContour(p, miniRadius.toPx(), density)
                        shape = PlayerWindowShape(Rect(0f, 0f, size.width, size.height), contour.radius, contour.bleed)
                        clip = p < 1f
                    }
                    .drawWithContent { if (playerPageVisible(state.visualProgress)) drawContent() },
            ) {
                key("player-destination-page") {
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
