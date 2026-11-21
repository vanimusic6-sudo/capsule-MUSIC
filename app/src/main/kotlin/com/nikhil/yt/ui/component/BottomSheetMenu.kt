/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
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

/**
 * Capsule Acrylic uses the actual source-backed BLUR material from
 * ArchiveTune's acrylic mini-player and navigation bar — never the glass
 * shader or the former flat "acrylic" gradient.
 *
 * Haze 2.1 by Chris Banes is Apache-2.0; ArchiveTune GPL-3.0 attribution
 * is retained above. Source capture is enabled only for an open menu.
 */
internal const val CAPSULE_MENU_ACRYLIC_BLUR_DP = 32
internal const val CAPSULE_MENU_ACRYLIC_NOISE = 0.12f
internal const val CAPSULE_MENU_ACRYLIC_TINT_ALPHA = 0.72f

/** Preserve the original Material3 sheet's appearance and THREE swipe anchors. */
enum class MenuPresentation { STANDARD, CAPSULE_PLAYER }

/** All menu action tiles use the same press isolation as the player. */
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
    return 1f - 0.032f * ease
}

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    internal var generation by mutableStateOf(0L)
        private set
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
        generation += 1L
        dialogContent = null
        this.presentation = presentation
        dismissRequested = false
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        if (isVisible) {
            dismissRequested = true
        } else {
            isVisible = false
        }
    }

    internal fun completeAnimatedDismiss(expectedGeneration: Long = generation) {
        if (dismissRequested && expectedGeneration == generation) {
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
    hazeState: HazeState? = null,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    if (state.isVisible) {
        // Original ArchiveTune/Material3 bottom sheet: same full-height modifier,
        // colours, handle and horizontal padding. Material keeps ownership of
        // drag, partialExpand/expand/hide and the LazyColumn nested scroll.
        // Our previous bespoke Dialog accidentally deleted and broke them.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val playerAppearance = state.presentation == MenuPresentation.CAPSULE_PLAYER
        val gel = remember { Animatable(0f) }
        val dismissPull = remember { Animatable(0f) }
        val density = LocalDensity.current
        // ArchiveTune's ACRYLIC style: 32dp backdrop diffusion, 12% grain
        // and 72% opaque material tint, not specular/liquid GLASS.
        // Keep the chosen explicit sheet colour; otherwise use M3 high
        // surface so the material is visibly softer than a black rectangle.
        val acrylicColor = if (background == MaterialTheme.colorScheme.surface)
            MaterialTheme.colorScheme.surfaceContainerHigh else background
        val acrylicStyle = remember(acrylicColor) {
            HazeBlurStyle {
                backgroundColor(acrylicColor)
                blurRadius(CAPSULE_MENU_ACRYLIC_BLUR_DP.dp)
                noiseFactor(CAPSULE_MENU_ACRYLIC_NOISE)
                colorEffects(
                    listOf(HazeColorEffect.tint(acrylicColor.copy(alpha = CAPSULE_MENU_ACRYLIC_TINT_ALPHA)))
                )
                fallbackColorEffect(HazeColorEffect.tint(acrylicColor))
            }
        }
        // A ModalBottomSheet lives in a separate Android window. Use
        // explicit Sources, not Backdrop: the latter would only capture
        // the sheet's OWN window and appear flat again.
        val acrylicInput = remember(hazeState) {
            hazeState?.let {
                HazeInput.Sources(
                    state = it,
                    retention = HazeSourceRetention.ClearWhenUnavailable,
                )
            }
        }
        val material = if (acrylicInput != null) Modifier.hazeBlur(
            input = acrylicInput,
            style = acrylicStyle,
            performanceMode = HazePerformanceMode.Balanced,
        ) else Modifier.background(acrylicColor)
        LaunchedEffect(Unit) {
            // Short, controlled impulse. This does NOT own any touch,
            // sheet height or anchor: the stock Material3 spring still
            // handles partial/full expansion and scroll coordination.
            gel.snapTo(-0.011f)
            gel.animateTo(
                0f,
                spring(
                    dampingRatio = 0.91f,
                    stiffness = 206f,
                    visibilityThreshold = 0.0001f,
                ),
            )
        }
        var observedGeneration by remember { mutableStateOf(state.generation) }
        LaunchedEffect(state.generation, state.dismissRequested) {
            val generation = state.generation
            val reopened = observedGeneration != generation
            observedGeneration = generation
            if (!state.dismissRequested && reopened) {
                // An interrupted exit must not leave the replacement menu shrunken or hidden.
                coroutineScope {
                    launch { dismissPull.animateTo(0f, spring(dampingRatio = 1f, stiffness = 295f)) }
                    if (sheetState.targetValue == androidx.compose.material3.SheetValue.Hidden) {
                        launch { sheetState.show() }
                    }
                }
            }
            if (state.dismissRequested) {
                // User swipe-down already finished Material's own hide. Do
                // not run a SECOND full exit on an invisible sheet: that was
                // leaving an empty modal window intercepting input.
                if (!sheetState.isVisible) {
                    state.completeAnimatedDismiss(generation)
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
                                dampingRatio = 0.97f,
                                stiffness = 295f,
                                visibilityThreshold = 0.001f,
                            ),
                        )
                    }
                    launch { sheetState.hide() }
                }
                state.completeAnimatedDismiss(generation)
            }
        }
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = {
                focusManager.clearFocus()
                state.dismiss()
            },
            containerColor = if (acrylicInput != null) Color.Transparent else acrylicColor,
            // Same 16dp softness as the VIDEO window; the previous 28dp
            // sheet curvature looked ragged during vertical travel.
            shape = if (playerAppearance) {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            } else MaterialTheme.shapes.extraLarge,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // A single material layer covers both handle and body. Do not
            // add a second gradient strip that introduces a seam at the lip.
            dragHandle = {
                Box(
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
                    )
                }
            },
            // Material3's default predictive-back scales the entire sheet
            // immediately on BACK-down/gesture-hold. Releasing then hands it
            // to the hide animation, producing the reported "shrinks then
            // jumps down" discontinuity. Opt out of ONLY that built-in
            // transformation for every menu; native drag, partial/full
            // expand, scrim and Material's dismissal remain untouched.
            properties = ModalBottomSheetDefaults.properties(
                shouldDismissOnBackPress = false,
            ),
            modifier = modifier.fillMaxHeight().then(material).graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                // Keep native sheet translation completely untouched.
                // A tiny launch recoil gives way to one committed-close
                // retreat: the bottom-anchored panel shrinks gently into
                // its downward exit without a separate snapping stage.
                val closeScale = capsuleMenuDismissScale(dismissPull.value)
                scaleX = capsuleMenuElasticX(gel.value) * closeScale
                scaleY = capsuleMenuElasticY(gel.value) * closeScale
                translationY = dismissPull.value * with(density) { 6.dp.toPx() }
            },
        ) {
            PredictiveBackHandler(enabled = !state.dismissRequested) { progress ->
                // Ignore progress (including prolonged holds). At the first
                // committed BACK event animate the native sheet from its
                // exact current anchor. A cancelled gesture moves nothing.
                progress.collect { }
                state.dismiss()
            }
            // Disable the edge-stretch/bounce inside ALL More menus, while
            // preserving native Material3 drag anchors and swipe dismissal.
            CompositionLocalProvider(
                LocalCapsuleMenuPressGuard provides true,
                LocalOverscrollFactory provides null,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // The material belongs to the sheet's single
                            // Haze layer; children must stay translucent.
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
