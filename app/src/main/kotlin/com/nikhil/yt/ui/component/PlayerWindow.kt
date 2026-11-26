/* Capsule MUSIC — shared-axis player scene. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * One live, full-size scene arrives over the stationary dock and home screen.
 * Its real background, cover, text, buttons and video share one transform.
 * No growing colored plate, moving replica, cached dock geometry, or
 * per-element animation. No synthetic material is drawn at any progress.
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
    val insets = WindowInsets.systemBars
    val direction = LocalLayoutDirection.current
    val localDensity = LocalDensity.current
    val leftInset = with(localDensity) { miniInset.toPx() } + insets.getLeft(localDensity, direction)
    val rightInset = with(localDensity) { miniInset.toPx() } + insets.getRight(localDensity, direction)
    val interaction = remember(state) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val compactInteractive by remember(state) { derivedStateOf { state.compactForegroundAcceptsInput } }
    LaunchedEffect(state, pressed, compactInteractive) {
        state.setCompactOpenPress(pressed && compactInteractive)
    }
    val renderPage by remember(state) { derivedStateOf {
        !state.isDismissed && (state.isExpandedOrExpanding || state.isAnimationRunning ||
            state.compactOpenPress || playerPageVisible(state.visualProgress))
    } }
    val sceneTransitioning by remember(state) { derivedStateOf {
        state.isAnimationRunning || state.compactOpenPress || (state.isExpandedOrExpanding && !state.isExpanded)
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
        playerSceneShouldInterceptTouch(state.visualProgress, state.isExpandedOrExpanding, state.isDismissed)
    } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(
        modifier.fillMaxSize().clipToBounds()
            .then(if (intercept) Modifier.pointerInput(state, leftInset, rightInset, miniHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val p = state.visualProgress
                    val dockTop = (state.expandedBound - state.collapsedBound).toPx()
                    val compactHit = state.compactForegroundAcceptsInput &&
                        down.position.x in leftInset..(size.width - rightInset) &&
                        down.position.y in dockTop..(dockTop + miniHeight.toPx())
                    if (!compactHit && (!state.isExpandedOrExpanding || !playerPageAcceptsInput(p))) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            } else Modifier),
    ) {
        // The original dock stays underneath the incoming scene, at its real
        // position and size. Glass therefore never moves a sampled backdrop.
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier.zIndex(if (compactInteractive) 2f else 0f)
                    .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                    .fillMaxWidth().height(miniHeight)
                    .graphicsLayer {
                        translationY = (state.collapsedBound - state.value).toPx().coerceAtLeast(0f)
                    }
                    .semantics { if (!compactInteractive) hideFromAccessibility() }
                    .then(if (compactSwipeDismissEnabled && onDismiss != null && compactInteractive)
                        Modifier.bottomSheetDraggable(
                            state = state, onDismiss = onDismiss, dismissOnlyFromCollapsed = true,
                            miniGestureArbiter = arbiter, compactDismissOnly = true,
                        ) else Modifier)
                    .then(if (compactInteractive) Modifier.clickable(
                        interactionSource = interaction, indication = null, onClick = state::expandSoft,
                    ) else Modifier)
                    .drawWithContent { if (sourceDraws) drawContent() },
            ) {
                CompositionLocalProvider(
                    LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                    LocalMiniPlayerGestureArbiter provides arbiter,
                ) { collapsedContent() }
            }
        }

        if (renderPage) {
            key("capsule-player-window") {
                Box(
                    Modifier.fillMaxSize().zIndex(1f)
                        .semantics { if (!pageAccessible) hideFromAccessibility() }
                        // An invisible full-size layer must not steal the dock's
                        // hit targets. Disable its pointer tree at compact rest.
                        .graphicsLayer {
                            val p = state.visualProgress
                            alpha = playerPresentationOpacity(p)
                            // An invisible composed page is prepared off-screen, not
                            // hit-testable over Home or the dock during the spring tail.
                            translationY = if (alpha > 0f) playerSceneTravelDp(p).dp.toPx()
                                else size.height
                            val scale = playerPresentationScale(p)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .drawWithContent { if (pageDraws) drawContent() },
                ) {
                    // Exactly one opaque scene beneath the outer opacity. All
                    // styles, photos, blur and controls retain native layout.
                    CompositionLocalProvider(
                        LocalCapsuleBackgroundMotionEnabled provides (pageDraws && !state.isAnimationRunning),
                        LocalPlayerSceneTransition provides sceneTransitioning,
                    ) {
                        Box(Modifier.fillMaxSize().background(backgroundColor)) {
                            Box(Modifier.fillMaxSize(), content = backgroundContent)
                            Box(Modifier.fillMaxSize(), content = content)
                        }
                    }
                }
            }
        }
    }
}

/** A cached artwork enters with the page instead of starting a separate reveal. */
internal val LocalPlayerSceneTransition = staticCompositionLocalOf { false }
