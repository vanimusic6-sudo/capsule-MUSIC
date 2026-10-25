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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny version of the existing CapsuleOrbitButton comet (ellipse, tail,
 * moving dot and centre). Animates ONLY while a More-sheet download row
 * is composed, never as hidden playback-wide background work.
 */
@Composable
internal fun CapsuleDownloadComet() {
    val rotation = rememberInfiniteTransition(label = "download-comet")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                // The same calm eight-second orbit as the player transport comet.
                animation = tween(8_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "download-orbit",
        )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val centerX = w / 2f
        val centerY = size.height / 2f
        val rx = w * .46f
        val ry = size.height * .19f
        rotate(-25f) {
            drawOval(
                color = color,
                topLeft = Offset(centerX - rx, centerY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = Stroke(width = w * .045f),
            )
            val angle = Math.toRadians(rotation.value.toDouble())
            val tailAngle = Math.toRadians((rotation.value - 15f).toDouble())
            val head = Offset(centerX + rx * cos(angle).toFloat(),
                centerY + ry * sin(angle).toFloat())
            val tail = Offset(centerX + rx * cos(tailAngle).toFloat(),
                centerY + ry * sin(tailAngle).toFloat())
            drawLine(color.copy(alpha = .35f), tail, head, strokeWidth = w * .035f)
            drawCircle(color, radius = w * .052f, center = head)
        }
        drawCircle(color, radius = w * .115f, center = Offset(centerX, centerY))
    }
}
