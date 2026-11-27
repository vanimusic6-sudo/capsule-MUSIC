/* Capsule MUSIC — coordinated mini-to-player transition. GPL-3.0. */
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Rect
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

/** One live mini and one rounded destination, driven by the same physical anchor. */
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
    val navigationHeight = LocalPlayerNavigationHeight.current
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
                    val miniTop = dockTop + playerMiniTravelPx(p, dockTop, density, state.frameVelocityFraction) +
                        (state.collapsedBound - state.value).toPx().coerceAtLeast(0f)
                    val compactHit = state.compactForegroundAcceptsInput &&
                        down.position.x in leftInset..(size.width - rightInset) &&
                        down.position.y in miniTop..(miniTop + miniHeight.toPx())
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
        // The actual compact card moves, not a captured replica.
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier.zIndex(2f)
                    .offset { IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx()) }
                    .fillMaxWidth().height(miniHeight)
                    .graphicsLayer {
                        val p = state.visualProgress
                        val dockTop = (state.expandedBound - state.collapsedBound).toPx()
                        alpha = playerMiniDrawAlpha(p)
                        // A retained, invisible mini cannot sit over the full player's
                        // hit targets. Move it outside the viewport only once alpha is zero.
                        translationY = if (alpha <= 0f) state.collapsedBound.toPx() + miniHeight.toPx()
                            else (state.collapsedBound - state.value).toPx().coerceAtLeast(0f) +
                                playerMiniTravelPx(p, dockTop, density, state.frameVelocityFraction)
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
                            val opacity = playerPresentationOpacity(p)
                            alpha = opacity
                            when {
                                opacity <= 0f -> {
                                    translationY = size.height
                                    shape = RectangleShape
                                    clip = false
                                }
                                p >= 1f -> {
                                    translationY = 0f
                                    shape = RectangleShape
                                    clip = false
                                }
                                else -> {
                                    val frame = playerPresentationBounds(p, size.width, size.height,
                                        (state.expandedBound - state.collapsedBound).toPx(),
                                        miniHeight.toPx(), leftInset, rightInset, navigationHeight.toPx())
                                    translationY = frame.top
                                    val radius = playerPageContour(p, miniRadius.toPx(), density).radius
                                        .coerceAtMost(minOf(frame.width, frame.height) * 0.5f)
                                    shape = PlayerWindowShape(Rect(frame.left, 0f, frame.right, frame.height), radius, 0f)
                                    clip = true
                                }
                            }
                        }
                        .drawWithContent { if (pageDraws) drawContent() },
                ) {
                    // One complete destination surface; all designs keep their native layout.
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

/** Actual app navigation height; zero for rail layouts and routes without a bar. */
internal val LocalPlayerNavigationHeight = staticCompositionLocalOf { 0.dp }

/** A cached artwork enters with the page instead of starting a separate reveal. */
internal val LocalPlayerSceneTransition = staticCompositionLocalOf { false }
