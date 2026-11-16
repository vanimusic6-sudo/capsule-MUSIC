/* Capsule MUSIC — continuous Mini -> Full container. No phantom duplicate player. GPL-3.0. */
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex

/**
 * One physical container, one anchored spring, two CONTENT states.
 *
 * Source: the actual compact card at the bottom, with its live controls and artwork.
 * Destination: the actual full player. The CONTAINER grows from the compact rectangle, but
 * full-player controls and Immersion artwork are never squashed into a miniature page.
 *
 * Apple-style continuity: same exact bounds forwards/backwards; no second animation clock.
 * Material-style container transform: opaque morphing mask, outgoing Mini yields to an incoming
 * full-size layout only after the container is large enough to hold it.
 *
 * The large full-screen clip is turned OFF at rest. The source and the app stop drawing behind
 * the fully opaque destination; when collapsed the destination is disposed, saving memory.
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
    motionEnabled: Boolean,
    transitionMediaId: String?,
    sharedArtworkEnabled: Boolean,
    collapsedContent: @Composable BoxScope.() -> Unit,
    backgroundContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val arbiter = remember(state) { MiniPlayerGestureArbiter() }
    val artworkHandoff = remember(state) { PlayerArtworkHandoff() }
    SideEffect { artworkHandoff.expect(transitionMediaId) }
    val renderPage by remember(state) {
        derivedStateOf {
            !state.isDismissed && (
                state.isExpandedOrExpanding ||
                    (state.isCollapsedOrCollapsing && state.isAnimationRunning) ||
                    shouldRenderExpandedSurface(state.visualProgress, state.isDismissed)
                )
        }
    }
    val sourceClock by remember(state, motionEnabled) {
        derivedStateOf { motionEnabled && !state.isDismissed &&
            playerMiniDrawAlpha(state.visualProgress) > 0f }
    }
    val pageDraws by remember(state, motionEnabled) {
        derivedStateOf { motionEnabled && playerPageVisible(state.visualProgress) }
    }
    val pageAccessible by remember(state) {
        derivedStateOf { playerPageAcceptsInput(state.visualProgress) }
    }
    val compactInteractive by remember(state) {
        derivedStateOf { state.compactForegroundAcceptsInput }
    }
    val windowInteractive by remember(state) {
        derivedStateOf { state.isExpandedOrExpanding || state.visualProgress > 0f }
    }
    val dismissingMini by remember(state) { derivedStateOf { state.rawProgress < 0f } }

    if (backHandlerEnabled && state.isExpandedOrExpanding) BackHandler(onBack = state::collapseSoft)

    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                if (artworkHandoff.rootBoundsInRoot != bounds)
                    artworkHandoff.rootBoundsInRoot = bounds
            }
            .offset {
                IntOffset(
                    0,
                    if (dismissingMini)
                        (state.collapsedBound - state.value).roundToPx() else 0,
                )
            }
            .clipToBounds()
            // A transparent full-size pointer node steals taps even when it consumes nothing.
            // Install it only for the finite transform or while the full player owns the page.
            .then(if (windowInteractive) Modifier.pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val p = state.visualProgress
                    if (p > 0f && p < 1f) {
                        val miniTop = size.height - state.collapsedBound.toPx() +
                            playerMiniLift(p, density)
                        val compactHit = state.compactForegroundAcceptsInput &&
                            down.position.x in miniInset.toPx()..(size.width - miniInset.toPx()) &&
                            down.position.y in miniTop..(miniTop + miniHeight.toPx())
                        val frame = playerPresentationBounds(
                            p, size.width.toFloat(), size.height.toFloat(),
                            size.height - state.collapsedBound.toPx(),
                            miniHeight.toPx(), miniInset.toPx(),
                        )
                        // Any temporarily exposed Home content is not a new touch destination.
                        if (!compactHit && (!playerPageAcceptsInput(p) ||
                                    !frame.contains(down.position))) {
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
        // Source: keep the REAL Mini fully mounted while animating, including interruptions.
        // It is a stable source at the bottom; the new card physically grows FROM its bounds.
        if (onDismiss == null || !state.isDismissed) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            0,
                            (state.expandedBound - state.collapsedBound).roundToPx(),
                        )
                    }
                    .zIndex(3f)
                    .semantics { if (!compactInteractive) hideFromAccessibility() }
                    .then(
                        if (compactInteractive && compactSwipeDismissEnabled && onDismiss != null)
                            Modifier.bottomSheetDraggable(
                                state = state,
                                onDismiss = onDismiss,
                                dismissOnlyFromCollapsed = true,
                                miniGestureArbiter = arbiter,
                                compactDismissOnly = true,
                            ) else Modifier,
                    )
                    .clickable(
                        enabled = compactInteractive,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state::expandSoft,
                    )
                    .fillMaxWidth().height(miniHeight)
                    .graphicsLayer {
                        val p = state.visualProgress
                        alpha = playerMiniDrawAlpha(p)
                        translationY = playerMiniLift(p, density)
                    }
                    .drawWithContent { if (sourceClock) drawContent() },
            ) {
                CompositionLocalProvider(
                    LocalCapsuleBackgroundMotionEnabled provides sourceClock,
                    LocalMiniPlayerGestureArbiter provides arbiter,
                    LocalPlayerArtworkHandoff provides artworkHandoff,
                ) { collapsedContent() }
            }
        }

        if (renderPage) {
            key("player-destination-page") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .semantics { if (!pageAccessible) hideFromAccessibility() }
                        .graphicsLayer {
                            val p = state.visualProgress
                            if (p < 0.999f) {
                                val frame = playerPresentationBounds(
                                    p, size.width, size.height,
                                    size.height - state.collapsedBound.toPx(),
                                    miniHeight.toPx(), miniInset.toPx(),
                                )
                                val contour = playerPageContour(
                                    p, miniRadius.toPx(), density,
                                )
                                shape = PlayerWindowShape(
                                    frame, contour.radius, contour.bleed,
                                )
                                clip = true
                            } else {
                                // No fullscreen clipping/render layer cost once settled.
                                shape = RectangleShape
                                clip = false
                            }
                        }
                        .drawWithContent { if (pageDraws) drawContent() },
                ) {
                    CompositionLocalProvider(
                        LocalCapsuleBackgroundMotionEnabled provides pageDraws,
                        LocalPlayerArtworkHandoff provides artworkHandoff,
                    ) {
                        // This material is always OPAQUE. Fading a large full-screen background
                        // made the home screen bleed THROUGH the album on bright artwork.
                        Box(
                            Modifier.fillMaxSize().background(backgroundColor)
                                .pointerInput(state) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        do {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                            content = backgroundContent,
                        )

                        // Real player/artwork remains its NATIVE layout and aspect ratio.
                        // It rises with the moving top of the frame and settles at 0 offset,
                        // avoiding a detached left-side crop and the huge scaled-up typography.
                        Box(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    val p = state.visualProgress
                                    val frame = playerPresentationBounds(
                                        p, size.width, size.height,
                                        size.height - state.collapsedBound.toPx(),
                                        miniHeight.toPx(), miniInset.toPx(),
                                    )
                                    alpha = playerContentOpacity(p)
                                    translationY = playerPresentationContentY(
                                        p, frame.top, density,
                                    )
                                }
                                .drawWithContent {
                                    if (motionEnabled && playerContentOpacity(
                                            state.visualProgress,
                                        ) > 0f) drawContent()
                                },
                            content = content,
                        )
                    }
                }
            }
        }
        // One shared bitmap travels between the actual Mini cover and the Immersion artwork.
        // Render ONLY during the anchored spring; at rest there is no extra graphics layer.
        if (sharedArtworkEnabled && motionEnabled && state.isAnimationRunning &&
            artworkHandoff.sourcePainter != null &&
            artworkHandoff.sourceBoundsInRoot != null &&
            artworkHandoff.destinationBoundsInWindow != null
        ) {
            PlayerSharedArtwork(
                handoff = artworkHandoff,
                progress = { state.visualProgress },
                modifier = Modifier.zIndex(4f),
            )
        }
    }
}
