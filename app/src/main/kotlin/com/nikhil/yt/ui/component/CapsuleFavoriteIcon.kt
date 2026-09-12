package com.nikhil.yt.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** The existing heart contour squeezes on press; its inner opening closes smoothly on like. */
@Composable
internal fun CapsuleFavoriteIcon(
    liked: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    interactionSource: InteractionSource? = null,
) {
    val pressed = interactionSource?.collectIsPressedAsState()?.value == true
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "favoritePress",
    )
    val fill by animateFloatAsState(if (liked) 1f else 0f, tween(260), label = "favoriteFill")
    val color by animateColorAsState(tint, tween(220), label = "favoriteTint")
    val label = stringResource(if (liked) R.string.action_remove_like else R.string.action_like)
    val path = remember { Path() }
    Canvas(modifier.size(24.dp).semantics { contentDescription = label }) {
        val progress = fill.coerceIn(0f, 1f)
        // Also deform when activated from accessibility, without a physical pointer press.
        val morph = (press + 0.65f * 4f * progress * (1f - progress) * (1f - press)).coerceIn(-0.16f, 1f)
        val opening = 1f - progress
        path.reset()
        path.fillType = PathFillType.EvenOdd
        path.moveTo(12f, 21.35f - morph * 1.1f)
        path.lineTo(10.55f, 20.03f)
        path.cubicTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
        path.cubicTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
        path.cubicTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
        path.cubicTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
        path.cubicTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
        path.cubicTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
        path.close()
        if (opening > 0f) {
            fun x(value: Float) = 12f + (value - 12f) * opening
            fun y(value: Float) = 11.8f + (value - 11.8f) * opening
            path.moveTo(x(12f), y(18.65f))
            path.cubicTo(x(7.14f), y(14.24f), x(4f), y(11.39f), x(4f), y(8.5f))
            path.cubicTo(x(4f), y(6.5f), x(5.5f), y(5f), x(7.5f), y(5f))
            path.cubicTo(x(9.04f), y(5f), x(10.54f), y(5.99f), x(11.07f), y(7.36f))
            path.lineTo(x(12.94f), y(7.36f))
            path.cubicTo(x(13.46f), y(5.99f), x(14.96f), y(5f), x(16.5f), y(5f))
            path.cubicTo(x(18.5f), y(5f), x(20f), y(6.5f), x(20f), y(8.5f))
            path.cubicTo(x(20f), y(11.39f), x(16.86f), y(14.24f), x(12f), y(18.65f))
            path.close()
        }
        val unit = minOf(size.width, size.height) / 24f
        translate((size.width - 24f * unit) / 2f, (size.height - 24f * unit) / 2f) {
            scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                scale(1f + 0.07f * morph, 1f - 0.12f * morph, pivot = androidx.compose.ui.geometry.Offset(12f, 12f)) {
                    drawPath(path, color)
                }
            }
        }
    }
}
