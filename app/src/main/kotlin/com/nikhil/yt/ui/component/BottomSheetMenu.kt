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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp

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
 * An intentionally non-draggable menu. Material's ModalBottomSheet treats a tiny
 * hold+finger drift as a sheet drag; the entire player then "backs away" before
 * the tap is released. A focused Dialog preserves Back, accessibility and
 * nested item dialogs, but only the *explicit* close request moves this surface.
 *
 * There is a single velocity-preserving Animatable. No recomposition, remeasure,
 * scroll reset or timer happens on individual animation frames: the whole sheet
 * and scrim travel in graphicsLayer.
 */
@Composable
private fun CapsulePhysicalMenuSheet(
    state: MenuState,
    background: Color,
    modifier: Modifier,
    onOutsideDismiss: () -> Unit,
) {
    val travel = remember(state) { Animatable(1f) }
    LaunchedEffect(state, state.dismissRequested) {
        if (state.dismissRequested) {
            travel.animateTo(
                1f,
                spring(dampingRatio = 1f, stiffness = 370f, visibilityThreshold = 0.001f),
            )
            state.completeAnimatedDismiss()
        } else {
            travel.animateTo(
                0f,
                spring(dampingRatio = 0.91f, stiffness = 215f, visibilityThreshold = 0.001f),
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
        // Disable the platform's constant window dim. Our dim uses the same
        // spring as the sheet, so dismissal never leaves a dark frame behind.
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val oldDim = window?.attributes?.dimAmount
            window?.setDimAmount(0f)
            onDispose { if (oldDim != null) window?.setDimAmount(oldDim) }
        }
        val density = LocalDensity.current
        val inertTouch = remember { MutableInteractionSource() }
        BoxWithConstraints(modifier.fillMaxSize()) {
            val fallbackTravelPx = with(density) { maxHeight.toPx() }
            var measuredSheetPx by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = capsuleMenuBackdropOpacity(travel.value) }
                    .background(Color.Black)
                    .clickable(
                        interactionSource = inertTouch,
                        indication = null,
                        onClick = onOutsideDismiss,
                    ),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.88f)
                    .onSizeChanged { measuredSheetPx = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = capsuleMenuSheetTranslation(
                            travel.value,
                            measuredSheetPx.takeIf { it > 0f } ?: fallbackTravelPx,
                        )
                    }
                    // Tapping/holding blank areas never reaches the scrim or
                    // changes the sheet's physical offset.
                    .clickable(
                        interactionSource = inertTouch,
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Box(Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp, bottom = 16.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    state.content(this)
                }
            }
        }
    }
}
