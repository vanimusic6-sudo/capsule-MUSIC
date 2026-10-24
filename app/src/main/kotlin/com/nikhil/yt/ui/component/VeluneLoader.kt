/* Capsule MUSIC — GPL-3.0 */
package com.nikhil.yt.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** One clock, a continuous tapered orbital trail, and no pulsing layout or composition per frame. */
@Composable
fun VeluneLoader(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color? = null) {
    val accent = color ?: MaterialTheme.colorScheme.primary
    val phase = rememberInfiniteTransition(label = "capsule-comet").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "orbit",
    )
    Canvas(modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val unit = minOf(this.size.width, this.size.height)
        val rx = unit * 0.41f
        val ry = unit * 0.29f
        fun point(degrees: Float): Offset {
            val radians = degrees * Math.PI / 180.0
            return center + Offset(rx * cos(radians).toFloat(), ry * sin(radians).toFloat())
        }
        rotate(-24f, center) {
            drawOval(accent.copy(alpha = accent.alpha * 0.12f), center - Offset(rx, ry), Size(rx * 2, ry * 2),
                style = Stroke((unit * 0.035f).coerceAtLeast(1.dp.toPx())))
            val head = phase.value
            // Adjacent short segments follow the ellipse; no long chords cut through the orbit.
            for (segment in 0 until 36) {
                val strength = (segment + 1) / 36f
                val angle = head - 100f + segment * (100f / 36f)
                drawLine(accent.copy(alpha = accent.alpha * strength * strength * 0.85f),
                    point(angle), point(angle + 100f / 36f),
                    (unit * (0.018f + 0.055f * strength)).coerceAtLeast(0.75.dp.toPx()), StrokeCap.Round)
            }
            val tip = point(head)
            drawCircle(accent.copy(alpha = accent.alpha * 0.12f), unit * 0.10f, tip)
            drawCircle(accent, (unit * 0.052f).coerceAtLeast(1.dp.toPx()), tip)
        }
        drawCircle(accent.copy(alpha = accent.alpha * 0.22f), unit * 0.12f)
        drawCircle(accent, unit * 0.058f)
    }
}
