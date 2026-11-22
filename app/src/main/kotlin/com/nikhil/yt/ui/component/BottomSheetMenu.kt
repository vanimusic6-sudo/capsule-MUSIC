/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * The menu lives in its own Android modal window. A backdrop capture from
 * the Activity is not guaranteed to render there and previously left the
 * Material container transparent, hiding content and overloading the GPU.
 *
 * Explicitly return an opaque colour, even when a caller requests an
 * alpha-bearing tint. Retain the default Material3 tonal hierarchy rather
 * than compositing a second full-window blur.
 */
internal fun capsuleMenuOpaqueSurface(
    requested: Color,
    themeSurface: Color,
    themeSurfaceHigh: Color,
): Color = (if (requested == themeSurface) themeSurfaceHigh else requested).copy(alpha = 1f)

/** Preserve the original Material3 sheet's appearance and THREE swipe anchors. */
enum class MenuPresentation { STANDARD, CAPSULE_PLAYER, LYRICS_COMPACT }

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
    return 1f - 0.055f * ease
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
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    if (state.isVisible) {
        if (state.presentation == MenuPresentation.LYRICS_COMPACT) {
            // Lyrics' fullscreen ink is drawn inside an animated, clipped
            // player RenderNode. Its native half-expanded sheet used to show
            // only the modal scrim on some HONOR/Android builds. A dedicated,
            // explicitly measured Dialog surfaces the compact controls above
            // the player instead of relying on a hidden sheet anchor.
            CompactLyricsMoreDialog(state, background)
            return
        }
        // Original ArchiveTune/Material3 bottom sheet: same full-height modifier,
        // colours, handle and horizontal padding. Material keeps ownership of
        // drag, partialExpand/expand/hide and the LazyColumn nested scroll.
        // Our previous bespoke Dialog accidentally deleted and broke them.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val playerAppearance = state.presentation != MenuPresentation.STANDARD
        val dismissPull = remember { Animatable(0f) }
        val sheetColor = capsuleMenuOpaqueSurface(
            requested = background,
            themeSurface = MaterialTheme.colorScheme.surface,
            themeSurfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        // One continuous color/grain material across handle, content and bottom inset.
        val sheetShape = if (playerAppearance) {
            RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        } else MaterialTheme.shapes.extraLarge
        var observedGeneration by remember { mutableStateOf(state.generation) }
        LaunchedEffect(state.generation, state.dismissRequested) {
            val generation = state.generation
            val reopened = observedGeneration != generation
            observedGeneration = generation
            if (!state.dismissRequested && reopened) {
                // An interrupted exit must not leave the replacement menu shrunken or hidden.
                coroutineScope {
                    launch { dismissPull.snapTo(0f) }
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
                        // A lengthy spring used to keep the invisible modal
                        // window alive after the native sheet had disappeared.
                        // End the decorative zoom before native hide completes.
                        dismissPull.animateTo(1f, tween(durationMillis = 145))
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
            // Let Material3 paint/clip the sheet to its *real anchored bounds*.
            // Painting the whole Modifier drew the acrylic over the exposed player
            // when the sheet was only half expanded (including the mini-player).
            containerColor = sheetColor.copy(alpha = 0.99f),
            // Same 16dp softness as the VIDEO window; the previous 28dp
            // sheet curvature looked ragged during vertical travel.
            shape = sheetShape,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // This is a cached top tint, not live blur or an offscreen
            // full-screen scrim. Its alpha follows the anchored offset in
            // the draw phase; no fast scroll recomposes the menu to fill it.
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithCache {
                            // Texture behind the native handle only, not the whole window.
                            onDrawBehind { drawRect(capsuleMenuGrain) }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
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
            // Apple-style committed dismissal: a small, reversible zoom-out
            // attached to the SAME native slide-down. Never runs while
            // dragging or holding Back; no extra layout animation.
            modifier = modifier.then(
                Modifier.fillMaxHeight()
            ).graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0.75f)
                val scale = capsuleMenuDismissScale(dismissPull.value)
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.10f * dismissPull.value
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
                            // Grain is constrained to native sheet content. It never
                            // draws behind the visible player above a partial sheet.
                            .drawWithCache {
                                onDrawBehind { drawRect(capsuleMenuGrain) }
                            }
                            .padding(horizontal = 16.dp),
                ) {
                    state.content(this)
                }
            }
        }
    }
}

/**
 * Lyrics-only "More" is always a real, bottom-aligned, bounded surface.
 * It does not depend on Material3's partially-expanded anchor, which can
 * leave only a dim/shimmer overlay visible above a fullscreen lyrics player.
 * The standard player/queue More menus keep the original anchored sheet.
 */
@Composable
private fun CompactLyricsMoreDialog(
    state: MenuState,
    background: Color,
) {
    val generation = state.generation
    val configuration = LocalConfiguration.current
    val sheetColor = capsuleMenuOpaqueSurface(
        requested = background,
        themeSurface = MaterialTheme.colorScheme.surface,
        themeSurfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    // A Lyrics-only close is immediate, so there is never a scrim window
    // stranded after pressing Back or after opening an edit/search dialog.
    LaunchedEffect(state.dismissRequested, generation) {
        if (state.dismissRequested) state.completeAnimatedDismiss(generation)
    }

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = state::dismiss,
                ),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = 180.dp,
                        max = (configuration.screenHeightDp * 0.76f).dp,
                    ),
                shape = shape,
                color = sheetColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithCache {
                            onDrawBehind { drawRect(capsuleMenuGrain) }
                        }
                        .padding(horizontal = 16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                ),
                        )
                    }
                    CompositionLocalProvider(
                        LocalCapsuleMenuPressGuard provides true,
                        LocalOverscrollFactory provides null,
                    ) {
                        state.content(this@Column)
                    }
                }
            }
        }
    }
}
