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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Preserve the original Material3 sheet's appearance and THREE swipe anchors. */
enum class MenuPresentation { STANDARD, CAPSULE_PLAYER }

/** Only action tiles in the Capsule player/lyrics menu need long-press isolation. */
val LocalCapsuleMenuPressGuard = compositionLocalOf { false }

/**
 * A bounded GPU-only soft-body response. This never controls sheet position,
 * swipe anchors or hit targets. Its spring receives the native sheet's
 * movement; at rest the menu is pixel-identical to the original.
 */
internal fun capsuleMenuElasticX(deformation: Float): Float =
    1f - 0.19f * deformation.coerceIn(-0.05f, 0.05f)

internal fun capsuleMenuElasticY(deformation: Float): Float =
    1f + deformation.coerceIn(-0.05f, 0.05f)

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
        if (presentation == MenuPresentation.CAPSULE_PLAYER && isVisible) {
            dismissRequested = true
        } else {
            isVisible = false
        }
    }

    internal fun completeAnimatedDismiss() {
        if (dismissRequested) {
            dismissRequested = false
            isVisible = false
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

    if (state.isVisible) {
        // Original ArchiveTune/Material3 bottom sheet: same full-height modifier,
        // colours, handle and horizontal padding. Material keeps ownership of
        // drag, partialExpand/expand/hide and the LazyColumn nested scroll.
        // Our previous bespoke Dialog accidentally deleted and broke them.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val physical = state.presentation == MenuPresentation.CAPSULE_PLAYER
        val gel = remember { Animatable(0f) }
        LaunchedEffect(physical) {
            if (physical) {
                // Slow enough to remain visible AFTER Material's initial
                // partial entrance has started. This is a soft-body effect on
                // pixels only, never a competing sheet translation/drag owner.
                gel.snapTo(-0.034f)
                gel.animateTo(
                    0f,
                    spring(
                        dampingRatio = 0.73f,
                        stiffness = 128f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
        }
        LaunchedEffect(state.dismissRequested) {
            if (state.dismissRequested) {
                // Do not destroy the surface before Material completes its
                // native gesture/programmatic close. Back, scrim and actions
                // all leave from the visible position and keep sheet anchors.
                coroutineScope {
                    // Exit contraction and native sheet slide happen together.
                    // Starting either on pointer DOWN is expressly forbidden.
                    launch {
                        gel.animateTo(
                            0.023f,
                            spring(
                                dampingRatio = 0.87f,
                                stiffness = 360f,
                                visibilityThreshold = 0.0001f,
                            ),
                        )
                    }
                    launch { sheetState.hide() }
                }
                state.completeAnimatedDismiss()
            }
        }
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = {
                focusManager.clearFocus()
                if (physical) state.dismiss() else state.isVisible = false
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
            CompositionLocalProvider(LocalCapsuleMenuPressGuard provides physical) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Only a tiny elastic response to opening, applied
                            // AFTER layout. No continuously repainted gradients,
                            // custom nested scroll or per-frame composition.
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = if (physical) capsuleMenuElasticX(gel.value) else 1f
                                scaleY = if (physical) capsuleMenuElasticY(gel.value) else 1f
                            }
                            .padding(horizontal = 20.dp),
                ) {
                    state.content(this)
                }
            }
        }
    }
}
