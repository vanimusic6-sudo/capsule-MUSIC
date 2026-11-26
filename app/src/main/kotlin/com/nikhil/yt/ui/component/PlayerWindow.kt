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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.flow.collect
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
    atomicScene: Boolean = false,
    sharedArtwork: Boolean = true,
) {
    val arbiter = remember(state) { MiniPlayerGestureArbiter() }
    val insets = WindowInsets.systemBars
    val layoutDirection = LocalLayoutDirection.current
    val localDensity = LocalDensity.current
    val leftInset = with(localDensity) { miniInset.toPx() } + insets.getLeft(localDensity, layoutDirection)
    val rightInset = with(localDensity) { miniInset.toPx() } + insets.getRight(localDensity, layoutDirection)
    val artwork = remember(state) { PlayerArtworkMotion(state) }
    SideEffect { artwork.sharedArtworkEnabled = sharedArtwork && !atomicScene }
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
        playerSceneShouldInterceptTouch(
            state.visualProgress, state.isExpandedOrExpanding, state.isDismissed)
    } }
    // Only the eligibility/identity changes cause a write. No per-frame bitmap
    // allocation, independent animation or image request belongs to the bridge.
    LaunchedEffect(artwork, state) {
        snapshotFlow {
            val p = state.visualProgress
            val phase = when { p <= 0f -> 0; p <= 0.10f -> 1; p < 0.92f -> 2; p < 1f -> 3; else -> 4 }
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
                    val p = state.visualProgress
                    if (p > 0f && p < 1f) {
                        val frame = playerPresentationBounds(p, size.width, size.height,
                            size.height - state.collapsedBound.toPx(), miniHeight.toPx(), leftInset, rightInset)
                        artwork.draw(this, frame, playerPageContour(p, miniRadius.toPx(), density).radius)
                    }
                }
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
            if (renderPage) {
                key("capsule-player-window") {
                    Box(
                        Modifier.fillMaxSize().zIndex(1f)
                            .semantics { if (!pageAccessible) hideFromAccessibility() }
                            .graphicsLayer {
                                val p = state.visualProgress
                                val frame = playerPresentationBounds(p, size.width, size.height,
                                    size.height - state.collapsedBound.toPx(), miniHeight.toPx(), leftInset, rightInset)
                                val contour = playerPageContour(p, miniRadius.toPx(), density)
                                shape = if (p >= 1f) RectangleShape else PlayerWindowShape(frame, contour.radius, 0f)
                                clip = p < 1f
                                shadowElevation = playerFrameShadowDp(p).dp.toPx()
                                // The shell is opaque. Only foreground and outgoing Mini change opacity.
                                alpha = playerPresentationOpacity(p)
                            }
                            .drawWithContent { if (pageDraws) drawContent() },
                    ) {
                        CompositionLocalProvider(LocalCapsuleBackgroundMotionEnabled provides pageDraws) {
                            Box(
                                Modifier.fillMaxSize().drawWithCache {
                                    val material = artwork.compactMaterial
                                    val bridge = material?.let {
                                        Brush.horizontalGradient(listOf(it.first, it.second))
                                    }
                                    onDrawWithContent {
                                        // Keep the same base underneath for the whole trip;
                                        // the destination fades over it, not through a third color.
                                        if (state.visualProgress < 1f && bridge != null) drawRect(bridge)
                                        else drawRect(backgroundColor)
                                        drawContent()
                                    }
                                }
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
                                content = {
                                    if (!atomicScene) Box(Modifier.fillMaxSize().graphicsLayer {
                                        alpha = playerMaterialReveal(state.visualProgress)
                                    }.background(backgroundColor), content = backgroundContent)
                                },
                            )
                            // Keep actual text, controls and photo in their final layout.
                            // A modest translation gives depth without sliding an entire
                            // screen-length canvas out of the compact card.
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    val p = state.visualProgress
                                    val frame = playerPresentationBounds(p, size.width, size.height,
                                        size.height - state.collapsedBound.toPx(), miniHeight.toPx(), leftInset, rightInset)
                                    // Full-bleed photographs reveal from their TOP, not
                                    // through a moving crop of their lower half. Light keeps
                                    // the destination measured at its actual final position.
                                    translationY = if (atomicScene) playerAtomicContentY(frame.top) else 0f
                                    alpha = if (atomicScene) playerAtomicSceneAlpha(p) else artwork.foregroundOpacity()
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
                        // Position BEFORE input modifiers so the tap and swipe hit
                        // targets coincide with the actual dock, including dismissal.
                        .offset {
                            IntOffset(0, (state.expandedBound - state.collapsedBound).roundToPx())
                        }
                        .fillMaxWidth().height(miniHeight)
                        .graphicsLayer {
                            val p = state.visualProgress
                            val dismiss = (state.collapsedBound - state.value).toPx().coerceAtLeast(0f)
                            translationY = dismiss
                            val release = 1f - (1f - pressScale) * (1f - playerPhase(p, 0f, 0.18f))
                            scaleX = release
                            scaleY = release
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            alpha = playerMiniDrawAlpha(p)
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
        }
    }
}
