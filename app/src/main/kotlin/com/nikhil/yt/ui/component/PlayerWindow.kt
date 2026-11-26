/* Capsule MUSIC — bounded container transform. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Material-style container transform: one real compact card, one clipped
 * destination with stable layout, and one track-scoped shared artwork.
 * The anchor owns position and velocity; the renderer has no second clock.
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
    val artwork = remember(state) { PlayerArtworkMotion(state) }
    val interaction = remember(state) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val compactInteractive by remember(state) { derivedStateOf { state.compactForegroundAcceptsInput } }
    LaunchedEffect(state, pressed, compactInteractive) {
        state.setCompactOpenPress(pressed && compactInteractive)
    }
    // Press feedback is independent of the travel only while docked; once the
    // card leaves its anchor, the anchor determines every visual phase.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && compactInteractive) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "player-dock-press",
    )
    val renderPage by remember(state) { derivedStateOf {
        !state.isDismissed && (state.isExpandedOrExpanding || state.isAnimationRunning ||
            state.compactOpenPress || playerPageVisible(state.visualProgress))
    } }
    val pageDraws by remember(state) { derivedStateOf { playerPageVisible(state.visualProgress) } }
    val sourceDraws by remember(state) { derivedStateOf {
        !state.isDismissed && playerMiniDrawAlpha(state.visualProgress) > 0f
    } }
    val sourceClock by remember(state) { derivedStateOf {
        sourceDraws && !state.isAnimationRunning && !state.isExpandedOrExpanding
    } }
    val pageAccessible by remember(state) { derivedStateOf {
        state.isExpandedOrExpanding && playerPageAcceptsInput(state.visualProgress)
    } }
    val intercept by remember(state) { derivedStateOf {
        !compactInteractive && playerSceneShouldInterceptTouch(
            state.visualProgress, state.isExpandedOrExpanding, state.isDismissed)
    } }
    // Only the eligibility/identity changes cause a write. No per-frame bitmap
    // allocation, independent animation or image request belongs to the bridge.
    LaunchedEffect(artwork, state) {
        snapshotFlow {
            val p = state.visualProgress
            val phase = when { p <= 0f -> 0; p <= 0.16f -> 1; p < 0.86f -> 2; p < 1f -> 3; else -> 4 }
            listOf(artwork.matchingId(), phase, state.isAnimationRunning,
                state.isExpandedOrExpanding, state.compactOpenPress, state.isDismissed)
        }.collect { artwork.reconcile() }
    }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    CompositionLocalProvider(LocalPlayerArtworkMotion provides artwork) {
        Box(
            modifier.fillMaxSize().clipToBounds()
                .onGloballyPositioned { artwork.root = it }
                .drawWithContent {
                    drawContent()
                    artwork.draw(this)
                }
                .then(if (intercept) Modifier.pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!state.isExpandedOrExpanding || !playerPageAcceptsInput(state.visualProgress)) {
                            down.consume()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } else Modifier),
        ) {
            if (renderPage) {
                key("capsule-player-window") {
                    Box(
                        Modifier.fillMaxSize().zIndex(1f)
                            .semantics { if (!pageAccessible) hideFromAccessibility() }
                            .graphicsLayer {
                                val p = state.visualProgress
                                val frame = playerPresentationBounds(p, size.width, size.height,
                                    size.height - state.collapsedBound.toPx(), miniHeight.toPx(), miniInset.toPx())
                                val contour = playerPageContour(p, miniRadius.toPx(), density)
                                shape = PlayerWindowShape(frame, contour.radius, 0f)
                                clip = p < 1f
                                shadowElevation = playerFrameShadowDp(p).dp.toPx()
                                // Only this short shell fade, not a second full-page alpha.
                                alpha = playerPresentationOpacity(p)
                            }
                            .drawWithContent { if (pageDraws) drawContent() },
                    ) {
                        CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides pageDraws) {
                            Box(
                                Modifier.fillMaxSize().background(backgroundColor)
                                    .then(if (intercept) Modifier.pointerInput(state) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            down.consume()
                                            do {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    } else Modifier),
                                content = backgroundContent,
                            )
                            // Keep actual text, controls and photo in their final layout.
                            // A modest translation gives depth without sliding an entire
                            // screen-length canvas out of the compact card.
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    val p = state.visualProgress
                                    val frame = playerPresentationBounds(p, size.width, size.height,
                                        size.height - state.collapsedBound.toPx(), miniHeight.toPx(), miniInset.toPx())
                                    translationY = playerPresentationContentY(p, frame.top, density)
                                    alpha = playerContentOpacity(p)
                                },
                                content = content,
                            )
                        }
                    }
                }
            }
            if (onDismiss == null || !state.isDismissed) {
                Box(
                    Modifier.zIndex(2f)
                        .semantics { if (!compactInteractive) hideFromAccessibility() }
                        .then(if (compactSwipeDismissEnabled && onDismiss != null) Modifier.bottomSheetDraggable(
                            state = state, onDismiss = onDismiss, dismissOnlyFromCollapsed = true,
                            miniGestureArbiter = arbiter, compactDismissOnly = true,
                        ) else Modifier)
                        .clickable(enabled = compactInteractive, interactionSource = interaction,
                            indication = null, onClick = state::expandSoft)
                        .fillMaxWidth().height(miniHeight)
                        .graphicsLayer {
                            val p = state.visualProgress
                            val dismiss = (state.collapsedBound - state.value).toPx().coerceAtLeast(0f)
                            translationY = (state.expandedBound - state.collapsedBound).toPx() + dismiss -
                                12.dp.toPx() * playerPhase(p, 0f, 0.40f)
                            val release = 1f - (1f - pressScale) * (1f - playerPhase(p, 0f, 0.18f))
                            scaleX = release
                            scaleY = release
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            alpha = playerMiniDrawAlpha(p)
                        }
                        .drawWithContent { if (sourceDraws) drawContent() },
                ) {
                    CompositionLocalProvider(
                        LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                        LocalMiniPlayerGestureArbiter provides arbiter,
                    ) { collapsedContent() }
                }
            }
        }
    }
}
