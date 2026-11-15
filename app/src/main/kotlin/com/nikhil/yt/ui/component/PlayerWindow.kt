/* Capsule MUSIC — one quiet, reversible presentation of the whole player. GPL-3.0. */
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
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

/** One transform for the real page, including its background, artwork, text and controls. */
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
    val sourceClock by remember(state) { derivedStateOf {
        !state.isDismissed && playerMiniDrawAlpha(state.visualProgress) > 0f
    } }
    val pageDraws by remember(state) { derivedStateOf { playerPageVisible(state.visualProgress) } }
    val pageAccessible by remember(state) { derivedStateOf { playerPageAcceptsInput(state.visualProgress) } }
    val compactInteractive by remember(state) { derivedStateOf { state.compactForegroundAcceptsInput } }
    val windowInteractive by remember(state) { derivedStateOf {
        state.isExpandedOrExpanding || state.visualProgress > 0f
    } }
    val dismissingMini by remember(state) { derivedStateOf { state.rawProgress < 0f } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(modifier.fillMaxSize()
        .offset { IntOffset(0, if (dismissingMini) (state.collapsedBound - state.value).roundToPx() else 0) }
        .clipToBounds()
        // An idle full-size pointer node wins sibling hit testing even if it consumes nothing.
        // Remove only that input node at rest; the layout/page identities remain stable.
        .then(if (windowInteractive) Modifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val p = state.visualProgress
                if (p > 0f && p < 1f) {
                    val miniTop = size.height - state.collapsedBound.toPx() + playerMiniLift(p, density)
                    val compactHit = state.compactForegroundAcceptsInput &&
                        down.position.x in miniInset.toPx()..(size.width - miniInset.toPx()) &&
                        down.position.y in miniTop..(miniTop + miniHeight.toPx())
                    val frame = playerPresentationBounds(p, size.width.toFloat(), size.height.toFloat(), density)
                    if (!compactHit && (!playerPageAcceptsInput(p) || !frame.contains(down.position))) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
        } else Modifier),
    ) {
        if (onDismiss == null || !state.isDismissed) {
            Box(Modifier
                .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                .zIndex(if (compactInteractive) 3f else 0f)
                .semantics { if (!compactInteractive) hideFromAccessibility() }
                .then(if (compactSwipeDismissEnabled && onDismiss != null) Modifier.bottomSheetDraggable(
                    state = state, onDismiss = onDismiss, dismissOnlyFromCollapsed = true,
                    miniGestureArbiter = arbiter, compactDismissOnly = true,
                ) else Modifier)
                .clickable(enabled = compactInteractive,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = state::expandSoft)
                .fillMaxWidth().height(miniHeight)
                .graphicsLayer {
                    val p = state.visualProgress
                    alpha = playerMiniDrawAlpha(p)
                    translationY = playerMiniLift(p, density)
                }
                .drawWithContent { if (sourceClock) drawContent() },
            ) {
                CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                    LocalMiniPlayerGestureArbiter provides arbiter) { collapsedContent() }
            }
        }
        if (renderPage) {
            key("player-destination-page") {
                Box(Modifier.fillMaxSize().zIndex(1f)
                    .semantics { if (!pageAccessible) hideFromAccessibility() }
                    .graphicsLayer {
                        val p = state.visualProgress
                        alpha = playerPresentationOpacity(p)
                        scaleX = playerPresentationScale(p)
                        scaleY = scaleX
                        translationY = playerPresentationLift(p, density)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        val contour = playerPageContour(p, miniRadius.toPx(), density)
                        shape = PlayerWindowShape(Rect(0f, 0f, size.width, size.height), contour.radius, contour.bleed)
                        clip = true
                    }
                    .drawWithContent { if (playerPageVisible(state.visualProgress)) drawContent() },
                ) {
                    CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides pageDraws) {
                        Box(Modifier.fillMaxSize().background(backgroundColor).pointerInput(state) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }, content = backgroundContent)
                        Box(Modifier.fillMaxSize(), content = content)
                    }
                }
            }
        }
    }
}
