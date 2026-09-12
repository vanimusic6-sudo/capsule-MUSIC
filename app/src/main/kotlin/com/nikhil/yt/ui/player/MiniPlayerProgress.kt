package com.nikhil.yt.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The original quiet track, shared by the standard mini-player and Capsule Dock. */
internal fun Modifier.miniPlayerProgress(position: Long, duration: Long): Modifier = drawWithContent {
    drawContent()
    val progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val diameter = size.minDimension
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    drawArc(
        color = Color(0xFF363640).copy(alpha = 0.2f),
        startAngle = 0f, sweepAngle = 360f, useCenter = false,
        topLeft = topLeft, size = arcSize, style = stroke,
    )
    drawArc(
        color = Color(0xFFF1F1F1),
        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
        topLeft = topLeft, size = arcSize, style = stroke,
    )
}
