/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Opt-in physical sheet, isolated to the full player and Lyrics. */
enum class MenuPresentation { STANDARD, CAPSULE_PLAYER }

internal fun capsuleMenuBackdropOpacity(travel: Float): Float =
    (1f - travel.coerceIn(0f, 1f)) * 0.38f

internal fun capsuleMenuSheetTranslation(travel: Float, measuredHeightPx: Float): Float =
    travel.coerceIn(0f, 1.08f) * measuredHeightPx.coerceAtLeast(0f)

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    var presentation by mutableStateOf(MenuPresentation.STANDARD)
        private set
    internal var dismissRequested by mutableStateOf(false)
        private set
    internal var dialogContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(
        presentation: MenuPresentation = MenuPresentation.STANDARD,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        dialogContent = null
        this.presentation = presentation
        dismissRequested = false
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        if (isVisible && presentation == MenuPresentation.CAPSULE_PLAYER) {
            // The menu stays composed until its exit spring actually settles.
            // Click/long-press never changes sheet position; only dismissal does.
            dismissRequested = true
        } else {
            isVisible = false
        }
    }

    internal fun completeAnimatedDismiss() {
        if (dismissRequested) {
            isVisible = false
            dismissRequested = false
        }
    }

    fun showDialog(content: @Composable () -> Unit) {
        dismissRequested = false
        isVisible = false
        dialogContent = content
    }

    fun dismissDialog() {
        dialogContent = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surface,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    if (state.isVisible && state.presentation == MenuPresentation.CAPSULE_PLAYER) {
        CapsulePhysicalMenuSheet(
            state = state,
            background = background,
            modifier = modifier,
            onOutsideDismiss = {
                focusManager.clearFocus()
                state.dismiss()
            },
        )
    } else if (state.isVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            containerColor = background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = {
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            },
            modifier = modifier.fillMaxHeight(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
            ) {
                state.content(this)
            }
        }
    }
}



/**
 * A menu with THREE physical anchors: expanded, peek, dismissed.
 * The previous non-draggable Dialog eliminated unintended press motion but
 * also deleted the essential swipe-to-expand and swipe-to-close controls.
 *
 * This preserves the long-press fix: only movement beyond Compose's touch slop
 * can begin a drag. Holding a button cannot affect either the sheet or scrim.
 * Normal inner LazyColumn scrolling still works; nested scroll only consumes
 * motion to expand the sheet or after the list reaches its top on a down-swipe.
 */
private const val CapsuleMenuPeek = 0.29f
private const val CapsuleMenuCloseThreshold = 0.56f

internal fun capsuleMenuSettleAnchor(progress: Float, velocityPx: Float): Float {
    if (velocityPx < -650f) return 0f
    if (velocityPx > 1300f) return 1f
    if (velocityPx > 650f) return if (progress >= CapsuleMenuPeek * 0.7f) 1f else CapsuleMenuPeek
    if (progress < CapsuleMenuPeek * 0.48f) return 0f
    if (progress >= CapsuleMenuCloseThreshold) return 1f
    return CapsuleMenuPeek
}

/** Subtle movement-bound gel; exactly 0 in the resting or fully hidden sheet. */
internal fun capsuleMenuJellyStretch(progress: Float, velocity: Float): Float {
    val envelope = sin((PI * progress.coerceIn(0f, 1f)).toFloat()).coerceAtLeast(0f)
    return ((-velocity * 0.0027f) * envelope).coerceIn(-0.018f, 0.018f)
}

@Composable
private fun CapsulePhysicalMenuSheet(
    state: MenuState,
    background: Color,
    modifier: Modifier,
    onOutsideDismiss: () -> Unit,
) {
    val travel = remember(state) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(CapsuleMenuPeek) }
    var settling by remember { mutableStateOf(false) }
    var measuredSheetPx by remember { mutableFloatStateOf(1f) }

    fun displayedProgress(): Float = if (dragging) dragProgress else travel.value

    fun moveBy(deltaPx: Float): Float {
        if (state.dismissRequested || settling || measuredSheetPx < 1f) return 0f
        if (!dragging) {
            dragging = true
            dragProgress = travel.value
            scope.launch { travel.stop() }
        }
        val previous = dragProgress
        dragProgress = (previous + deltaPx / measuredSheetPx).coerceIn(0f, 1f)
        return (dragProgress - previous) * measuredSheetPx
    }

    fun release(velocityPx: Float) {
        if (!dragging || settling) return
        settling = true
        // Snapshot the gesture endpoint before the next frame's spring starts.
        val releasedAt = dragProgress
        val anchor = capsuleMenuSettleAnchor(releasedAt, velocityPx)
        scope.launch {
            travel.snapTo(releasedAt)
            dragging = false
            travel.animateTo(
                anchor,
                spring(
                    dampingRatio = if (anchor == 1f) 0.91f else 0.82f,
                    stiffness = if (anchor == 1f) 360f else 310f,
                    visibilityThreshold = 0.001f,
                ),
                initialVelocity = (velocityPx / measuredSheetPx).coerceIn(-6f, 6f),
            )
            settling = false
            if (anchor >= 1f) state.dismiss()
        }
    }

    LaunchedEffect(state, state.dismissRequested) {
        if (state.dismissRequested) {
            // Explicit menu action/back/scrim, not finger-down: exit starts here.
            dragging = false
            travel.animateTo(
                1f,
                spring(dampingRatio = 0.92f, stiffness = 365f, visibilityThreshold = 0.001f),
            )
            state.completeAnimatedDismiss()
        } else {
            travel.animateTo(
                CapsuleMenuPeek,
                spring(dampingRatio = 0.80f, stiffness = 255f, visibilityThreshold = 0.001f),
            )
        }
    }

    Dialog(
        onDismissRequest = onOutsideDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val oldDim = window?.attributes?.dimAmount
            window?.setDimAmount(0f)
            onDispose { if (oldDim != null) window?.setDimAmount(oldDim) }
        }
        val density = LocalDensity.current
        val touchSource = remember { MutableInteractionSource() }

        val nestedDrag = remember(state, travel, scope) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput ||
                        available.y >= 0f || state.dismissRequested ||
                        displayedProgress() <= 0f
                    ) return Offset.Zero
                    return Offset(0f, moveBy(available.y))
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput ||
                        available.y <= 0f || state.dismissRequested
                    ) return Offset.Zero
                    return Offset(0f, moveBy(available.y))
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    // Child scroll consumed its own fling first. Only settle if
                    // the sheet itself was moved by this gesture.
                    if (dragging) release(available.y)
                    return Velocity.Zero
                }
            }
        }
        val handleDrag = rememberDraggableState { delta -> moveBy(delta) }
        BoxWithConstraints(modifier.fillMaxSize()) {
            val fallbackTravelPx = with(density) { maxHeight.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = capsuleMenuBackdropOpacity(displayedProgress()) }
                    .background(Color.Black)
                    .clickable(
                        interactionSource = touchSource,
                        indication = null,
                        onClick = onOutsideDismiss,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.92f)
                    .testTag("capsule-menu-card")
                    .onSizeChanged { measuredSheetPx = it.height.toFloat() }
                    .graphicsLayer {
                        val progress = displayedProgress()
                        translationY = capsuleMenuSheetTranslation(
                            progress, measuredSheetPx.takeIf { it > 1f } ?: fallbackTravelPx,
                        )
                        val gel = if (dragging) 0f else
                            capsuleMenuJellyStretch(progress, travel.velocity)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleX = 1f + gel
                        scaleY = 1f - gel * 0.48f
                    }
                    .nestedScroll(nestedDrag)
                    .clickable(
                        interactionSource = touchSource,
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    // The dedicated handle is always draggable in either direction.
                    // Modifier.draggable waits for touch slop, so a long hold
                    // without moving never makes the entire menu recede.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .testTag("capsule-menu-drag-handle")
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = handleDrag,
                                onDragStarted = { if (!dragging) {
                                    dragProgress = travel.value
                                    dragging = true
                                    scope.launch { travel.stop() }
                                } },
                                onDragStopped = { velocity -> release(velocity) },
                            )
                            .padding(top = 14.dp, bottom = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                    }
                    state.content(this)
                }
            }
        }
    }
}
