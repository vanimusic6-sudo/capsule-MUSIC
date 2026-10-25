package com.nikhil.yt.ui.menu

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Download comet only exists while the user has the More sheet open.
 * No background frame callback survives dismissing that sheet.
 */
@Composable
internal fun CapsuleDownloadComet() {
    val phase = rememberInfiniteTransition(label = "download-comet")
        .animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing),
                repeatMode = RepeatMode.Restart),
            label = "download-comet-angle",
        )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(23.dp)) {
        val strokeWidth = 2.25.dp.toPx()
        val pad = strokeWidth * 1.45f
        val rect = Size(size.width - 2 * pad, size.height - 2 * pad)
        rotate(phase.value) {
            drawArc(color.copy(alpha = .20f), -90f, 250f, false,
                topLeft = Offset(pad, pad), size = rect,
                style = Stroke(strokeWidth, cap = StrokeCap.Round))
            drawArc(color, 110f, 56f, false,
                topLeft = Offset(pad, pad), size = rect,
                style = Stroke(strokeWidth, cap = StrokeCap.Round))
            val headAngle = Math.toRadians(166.0)
            val radius = rect.width / 2f
            val head = Offset(center.x + cos(headAngle).toFloat() * radius,
                center.y + sin(headAngle).toFloat() * radius)
            drawCircle(color, strokeWidth * .68f, center = head)
        }
    }
}
