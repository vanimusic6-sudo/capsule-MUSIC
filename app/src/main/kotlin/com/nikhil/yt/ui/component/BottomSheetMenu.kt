/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.material3.ModalBottomSheetDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

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

/**
 * Top tint follows the REAL sheet's drag offset, not an elapsed-time animator.
 * The soft cubic endpoints mean expanding from the partial anchor never
 * exposes a suddenly recoloured top edge, including on 60 Hz devices.
 */
internal fun capsuleMenuTopFill(offsetPx: Float, viewportPx: Float): Float {
    if (!offsetPx.isFinite() || viewportPx <= 0f) return 0f
    val expanded = (1f - offsetPx / viewportPx).coerceIn(0f, 1f)
    val t = ((expanded - 0.24f) / 0.70f).coerceIn(0f, 1f)
    return (t * t * (3f - 2f * t) * 0.30f)
}

/** A short, continuous Apple/HONOR-style retreat during committed close ONLY. */
internal fun capsuleMenuDismissScale(close: Float): Float {
    val p = close.coerceIn(0f, 1f)
    val ease = p * p * (3f - 2f * p)
    return 1f - 0.052f * ease
}

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
        val dismissPull = remember { Animatable(0f) }
        val density = LocalDensity.current
        val viewportPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val upperTint = MaterialTheme.colorScheme.surfaceContainerHighest
        val haze = remember(upperTint) {
            Brush.verticalGradient(
                0f to upperTint.copy(alpha = 0.55f),
                1f to upperTint.copy(alpha = 0f),
            )
        }
        LaunchedEffect(physical) {
            if (physical) {
                // Short, controlled impulse. This does NOT own any touch,
                // sheet height or anchor: the stock Material3 spring still
                // handles partial/full expansion and scroll coordination.
                gel.snapTo(-0.017f)
                gel.animateTo(
                    0f,
                    spring(
                        dampingRatio = 0.80f,
                        stiffness = 270f,
                        visibilityThreshold = 0.0001f,
                    ),
                )
            }
        }
        LaunchedEffect(state.dismissRequested) {
            if (state.dismissRequested) {
                // User swipe-down already finished Material's own hide. Do
                // not run a SECOND full exit on an invisible sheet: that was
                // leaving an empty modal window intercepting input.
                if (!sheetState.isVisible) {
                    state.completeAnimatedDismiss()
                    return@LaunchedEffect
                }
                // Do not destroy the surface before Material completes its
                // native gesture/programmatic close. Back, scrim and actions
                // all leave from the visible position and keep sheet anchors.
                coroutineScope {
                    // Exit contraction and native sheet slide happen together.
                    // Starting either on pointer DOWN is expressly forbidden.
                    launch {
                        dismissPull.animateTo(
                            1f,
                            spring(
                                dampingRatio = 1.0f,
                                stiffness = 390f,
                                visibilityThreshold = 0.001f,
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
            // Same 16dp softness as the VIDEO window; the previous 28dp
            // sheet curvature looked ragged during vertical travel.
            shape = if (physical) {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            } else MaterialTheme.shapes.extraLarge,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // The haze belongs to the TOP lip only, not a costly
            // full-screen scrim. Its alpha follows the anchored offset in
            // the draw phase; no fast scroll recomposes the menu to fill it.
            dragHandle = {
                if (physical) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(28.dp)
                                .graphicsLayer {
                                    // Material initializes the anchored offset
                                    // only AFTER its first measure. An alpha
                                    // read during the initial graphics-layer
                                    // placement otherwise crashes before the
                                    // sheet can open on Android/Robolectric.
                                    val offset = try {
                                        sheetState.requireOffset()
                                    } catch (_: IllegalStateException) {
                                        viewportPx
                                    }
                                    alpha = capsuleMenuTopFill(offset, viewportPx)
                                }
                                .background(haze),
                        )
                        Box(
                            Modifier.size(width = 40.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                ),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            ),
                    )
                }
            },
            // Material3's default predictive-back scales the entire sheet
            // immediately on BACK-down/gesture-hold. Releasing then hands it
            // to the hide animation, producing the reported "shrinks then
            // jumps down" discontinuity. Opt out of ONLY that built-in
            // transformation for player/Lyrics; native drag, partial/full
            // expand, scrim and Material's dismissal remain untouched.
            properties = ModalBottomSheetDefaults.properties(
                shouldDismissOnBackPress = !physical,
            ),
            modifier = modifier.fillMaxHeight().graphicsLayer {
                if (physical) {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // Keep native sheet translation completely untouched.
                    // A tiny launch recoil gives way to one committed-close
                    // retreat: the bottom-anchored panel shrinks gently into
                    // its downward exit without a separate snapping stage.
                    val closeScale = capsuleMenuDismissScale(dismissPull.value)
                    scaleX = capsuleMenuElasticX(gel.value) * closeScale
                    scaleY = capsuleMenuElasticY(gel.value) * closeScale
                    translationY = dismissPull.value * with(density) { 9.dp.toPx() }
                }
            },
        ) {
            PredictiveBackHandler(enabled = physical && !state.dismissRequested) { progress ->
                // Ignore progress (including prolonged holds). At the first
                // committed BACK event animate the native sheet from its
                // exact current anchor. A cancelled gesture moves nothing.
                progress.collect { }
                state.dismiss()
            }
            CompositionLocalProvider(LocalCapsuleMenuPressGuard provides physical) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Only a tiny elastic response to opening, applied
                            // AFTER layout. No continuously repainted gradients,
                            // custom nested scroll or per-frame composition.
                            // No second scale applied to child controls:
                            // one outer GPU layer owns both the body and handle.
                            .padding(horizontal = 20.dp),
                ) {
                    state.content(this)
                }
            }
        }
    }
}
