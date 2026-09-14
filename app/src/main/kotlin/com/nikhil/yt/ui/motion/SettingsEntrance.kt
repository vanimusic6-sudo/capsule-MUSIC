package com.nikhil.yt.ui.motion

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The settings list's staggered entrance.
 *
 * This restores the cascade the previous version had, and fixes what made it look broken. That
 * version drove each card with `AnimatedVisibility` + `slideInVertically`, which translates a card
 * without reserving the space it travels through, so a card slid over the neighbour already parked
 * at its final position. It also keyed every card to a `val ... = true` constant, which means
 * `AnimatedVisibility` started in its visible state and the entrance never actually ran.
 *
 * Here the cascade is pure `graphicsLayer`: the layout is fixed from the first frame and only the
 * drawing moves, so cards physically cannot overlap. Each card also carries a short blur that
 * resolves as it lands — the sense of speed, gone by the time the card is readable.
 */
private const val SettingsEntranceDurationMillis = 420
private const val SettingsEntranceStride = 0.085f
private const val SettingsEntranceLiftPx = 26f
private val SettingsEntranceBlurRadius = 9.dp

private val supportsRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Drives one settings entrance from 0 to 1 and then completes.
 *
 * The whole cascade shares a single animation rather than one per card, so its total length does
 * not grow with the number of rows on screen and there is exactly one coroutine, which finishes.
 */
@Composable
fun rememberSettingsEntrance(): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = SettingsEntranceDurationMillis))
    }
    return progress.asState()
}

/**
 * Applies one card's share of [entrance].
 *
 * Draw-phase only: this never changes measurement, so it cannot disturb the list's layout, its
 * scrolling, or its neighbours.
 */
fun Modifier.settingsEntranceItem(entrance: State<Float>, index: Int): Modifier = graphicsLayer {
    val settled = CapsuleMotion.staggered(entrance.value, index, SettingsEntranceStride)

    alpha = settled.coerceIn(0f, 1f)
    translationY = (1f - settled) * SettingsEntranceLiftPx

    val blurPx =
        CapsuleMotion.speedBlurPx(
            weight = 1f - settled,
            maxRadiusPx = SettingsEntranceBlurRadius.toPx(),
        )
    renderEffect =
        if (blurPx > 0f && supportsRenderEffect) {
            BlurEffect(blurPx, blurPx, TileMode.Decal)
        } else {
            null
        }
}
