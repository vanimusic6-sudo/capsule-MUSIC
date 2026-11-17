package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import kotlinx.coroutines.delay
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/**
 * Capsule heart with interaction-driven motion.
 *
 * Database/track restoration is snapped directly to the final state. The fill animation is only
 * armed by a real press, so opening a song that is already liked never replays the like animation.
 */
@Composable
internal fun CapsuleFavoriteIcon(
    liked: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    interactionSource: InteractionSource? = null,
    userActionToken: Int = 0,
) {
    val pressed = interactionSource?.collectIsPressedAsState()?.value == true
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 540f),
        label = "favoritePress",
    )

    val fill = remember { Animatable(if (liked) 1f else 0f) }
    var userInteractionPending by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            userInteractionPending = true
        } else if (userInteractionPending) {
            // Room/network state can land a little after ACTION_UP. Keep a short interaction window
            // so a genuine tap still gets its animation, then disarm to prevent unrelated updates
            // (track restore/sync) from animating later.
            delay(900)
            userInteractionPending = false
        }
    }

    /*
     * Reorder/edit containers are allowed to observe the pointer stream around this button. In
     * that case Compose may not deliver a PressInteraction reliably enough for the heart to infer
     * that the coming database change belongs to this tap. The owner can therefore arm the same
     * animation explicitly with a monotonically increasing token.
     */
    LaunchedEffect(userActionToken) {
        if (userActionToken == 0) return@LaunchedEffect
        userInteractionPending = true
        delay(2_500)
        userInteractionPending = false
    }

    LaunchedEffect(liked) {
        val target = if (liked) 1f else 0f
        if (fill.value == target) return@LaunchedEffect

        if (userInteractionPending) {
            userInteractionPending = false
            fill.animateTo(target, tween(260, easing = CapsuleStandardEasing))
        } else {
            fill.snapTo(target)
        }
    }

    val color = if (liked) CapsuleFavoriteColors.active else tint
    val label = stringResource(if (liked) R.string.action_remove_like else R.string.action_like)
    val path = remember { Path() }

    Canvas(modifier.size(24.dp).semantics { contentDescription = label }) {
        // Snap microscopically subpixel residues to their exact logical endpoints.
        // Otherwise bitmap rasterisation can remain 1–2px narrower after a tap.
        val progress = fill.value.coerceIn(0f, 1f).let {
            when { it <= 0.003f -> 0f; it >= 0.997f -> 1f; else -> it }
        }
        val pressIntensity = press.coerceIn(0f, 1f).let { if (it < 0.008f) 0f else it }
        // Crisp, optically stable rounded-heart contour at both rest endpoints.
        val morph = (
            pressIntensity +
                0.65f * 4f * progress * (1f - progress) * (1f - pressIntensity)
        ).coerceIn(0f, 1f)
        val opening = 1f - progress

        path.reset()
        path.fillType = PathFillType.EvenOdd
        // Soft, plump contour with rounded shoulders and a gentle tip.
        // Keep full glyph size and the existing user-only fill animation.
        path.moveTo(12f, 20.8f - morph * 0.75f)
        path.cubicTo(11.48f, 20.8f, 10.81f, 20.2f, 10.07f, 19.54f)
        path.cubicTo(6.39f, 16.27f, 2.5f, 12.86f, 2.5f, 8.94f)
        path.cubicTo(2.5f, 5.77f, 4.88f, 3.55f, 7.8f, 3.55f)
        path.cubicTo(9.49f, 3.55f, 10.94f, 4.33f, 12f, 5.52f)
        path.cubicTo(13.06f, 4.33f, 14.51f, 3.55f, 16.2f, 3.55f)
        path.cubicTo(19.12f, 3.55f, 21.5f, 5.77f, 21.5f, 8.94f)
        path.cubicTo(21.5f, 12.86f, 17.61f, 16.27f, 13.93f, 19.54f)
        path.cubicTo(13.19f, 20.2f, 12.52f, 20.8f - morph * 0.75f, 12f, 20.8f - morph * 0.75f)
        path.close()

        if (opening > 0f) {
            fun x(value: Float) = 12f + (value - 12f) * opening
            fun y(value: Float) = 11.8f + (value - 11.8f) * opening

            path.moveTo(x(12f), y(18.12f))
            path.cubicTo(x(10.05f), y(16.35f), x(4.51f), y(12.16f), x(4.51f), y(8.92f))
            path.cubicTo(x(4.51f), y(6.94f), x(5.94f), y(5.57f), x(7.82f), y(5.57f))
            path.cubicTo(x(9.35f), y(5.57f), x(10.56f), y(6.54f), x(12f), y(8.13f))
            path.cubicTo(x(13.44f), y(6.54f), x(14.65f), y(5.57f), x(16.18f), y(5.57f))
            path.cubicTo(x(18.06f), y(5.57f), x(19.49f), y(6.94f), x(19.49f), y(8.92f))
            path.cubicTo(x(19.49f), y(12.16f), x(13.95f), y(16.35f), x(12f), y(18.12f))
            path.close()
        }

        val unit = minOf(size.width, size.height) / 24f
        translate((size.width - 24f * unit) / 2f, (size.height - 24f * unit) / 2f) {
            scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                scale(
                    1f + 0.07f * morph,
                    1f - 0.12f * morph,
                    pivot = androidx.compose.ui.geometry.Offset(12f, 12f),
                ) {
                    drawPath(path, color)
                }
            }
        }
    }
}
