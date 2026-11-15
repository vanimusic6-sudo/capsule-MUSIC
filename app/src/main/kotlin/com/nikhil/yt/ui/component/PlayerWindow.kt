/* Capsule MUSIC — a shared Mini container, artwork and title. GPL-3.0. */
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
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * One existing anchored spring drives a real measured container and paired foreground elements.
 * The page retains its final layout. Shared artwork/title leave their native slots only while
 * the overlay is active; no whole-page zoom, moving page, screenshot or second animation clock.
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
    val morph = remember(state) { PlayerSharedMorph(state) }
    val arbiter = remember(state) { MiniPlayerGestureArbiter() }
    val bodyLayer = rememberGraphicsLayer()
    val handoffInteraction = remember { MutableInteractionSource() }
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
    val compactAtRest by remember(state) { derivedStateOf { state.visualProgress == 0f } }
    val dismissingMini by remember(state) { derivedStateOf { state.rawProgress < 0f } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    CompositionLocalProvider(LocalPlayerSharedMorph provides morph) {
    Box(modifier.fillMaxSize()
        .offset { IntOffset(0, if (dismissingMini) (state.collapsedBound - state.value).roundToPx() else 0) }
        // One full-viewport owner invalidates ALL transition pixels, including below the Mini.
        // No translated child can leave a cover strip outside its parent's invalidation bounds.
        .clipToBounds()
        .onGloballyPositioned { morph.origin = it.localToRoot(Offset.Zero) }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val p = state.visualProgress
                if (p > 0f && p < 1f) {
                    val frame = morph.container(Size(size.width.toFloat(), size.height.toFloat()),
                        miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                    val compactHit = state.compactForegroundAcceptsInput &&
                        down.position.x in frame.left..frame.right &&
                        down.position.y in frame.top..(frame.top + miniHeight.toPx())
                    if (!compactHit && (!playerPageAcceptsInput(p) || !frame.contains(down.position))) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
        }
        .drawWithContent {
            val p = state.visualProgress
            if (p <= 0f || p >= 1f) {
                drawContent()
            } else {
                val frame = morph.container(size, miniHeight.toPx(), state.collapsedBound.toPx(), miniInset.toPx())
                val contour = playerPageContour(p, miniRadius.toPx(), density)
                val path = Path().apply {
                    addRoundRect(RoundRect(Rect(frame.left-contour.bleed, frame.top-contour.bleed,
                        frame.right+contour.bleed, frame.bottom+contour.bleed),
                        CornerRadius(contour.radius, contour.radius)))
                }
                clipPath(path) {
                    drawContent()
                    with(morph) { paint(frame) }
                }
            }
        },
    ) {
        if (onDismiss == null || !state.isDismissed) {
            Box(Modifier
                .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                .zIndex(if (compactAtRest) 2f else 0f)
                .semantics { if (!state.compactForegroundAcceptsInput) hideFromAccessibility() }
                .then(if (compactSwipeDismissEnabled && onDismiss != null) Modifier.bottomSheetDraggable(
                    state = state, onDismiss = onDismiss, dismissOnlyFromCollapsed = true,
                    miniGestureArbiter = arbiter, compactDismissOnly = true,
                ) else Modifier)
                .clickable(enabled = state.compactForegroundAcceptsInput,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = state::expandSoft)
                .fillMaxWidth().height(miniHeight),
            ) {
                CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                    LocalMiniPlayerGestureArbiter provides arbiter) { collapsedContent() }
            }
        }
        if (renderPage) {
            key("player-destination-page") {
                Box(Modifier.fillMaxSize()
                    .semantics { if (!pageAccessible) hideFromAccessibility() }
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
                        Box(Modifier.fillMaxSize().drawWithContent {
                            // Recording still visits the matched elements while the rest of the
                            // page is transparent. The shared cover is never gated by body alpha.
                            val p = state.visualProgress
                            if (p >= 1f) drawContent() else {
                                bodyLayer.record { this@drawWithContent.drawContent() }
                                bodyLayer.alpha = playerBodyOpacity(p)
                                drawLayer(bodyLayer)
                            }
                        }, content = content)
                    }
                }
            }
        }
        // Stable hit region follows the displayed source, not its stationary measurement slot.
        Box(Modifier.offset {
            IntOffset(0, playerSurfaceTop(state.visualProgress, state.expandedBound.toPx(),
                state.collapsedBound.toPx()).roundToInt())
        }.fillMaxWidth().height(miniHeight).zIndex(if (morph.active) 5f else -1f)
            .testTag("player-mini-handoff")
            .semantics { hideFromAccessibility() }
            .then(if (morph.active && state.compactForegroundAcceptsInput) {
                Modifier.then(if (compactSwipeDismissEnabled && onDismiss != null)
                    Modifier.bottomSheetDraggable(state = state, onDismiss = onDismiss,
                        dismissOnlyFromCollapsed = true, miniGestureArbiter = arbiter,
                        compactDismissOnly = true) else Modifier)
                    .clickable(interactionSource = handoffInteraction, indication = null,
                        onClick = state::expandSoft)
            } else Modifier))
    }
    }
}
