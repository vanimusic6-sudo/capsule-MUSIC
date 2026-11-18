/* Capsule MUSIC — quiet, tactile lyrics volume controls. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.min

/**
 * Compact rectangular capsule, not a bulbous pill. Fine ink ticks are evenly spaced
 * across the real filled region. Empty and filled parts share one geometry.
 * Nothing runs on a timer: drawing only responds to actual volume updates.
 */
@Composable
internal fun CapsuleLyricsVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val volume = value.coerceIn(0f, 1f)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    Canvas(
        modifier = modifier
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(volume, 0f..1f)
                setProgress { next ->
                    latestOnValueChange(next.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                fun sendFromX(x: Float) {
                    latestOnValueChange((x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    sendFromX(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        sendFromX(change.position.x)
                        if (!change.pressed) break
                        change.consume()
                    }
                }
            },
    ) {
        val height = 26.dp.toPx().coerceAtMost(size.height)
        val top = (size.height - height) * 0.5f
        val radius = min(6.dp.toPx(), height * 0.5f)
        val fullCorner = CornerRadius(radius)
        val activeWidth = (size.width * volume).coerceIn(0f, size.width)
        val bounds = Rect(0f, top, size.width, top + height)
        val silhouette = Path().apply { addRoundRect(RoundRect(bounds, fullCorner)) }

        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(0f, top),
            size = Size(size.width, height),
            cornerRadius = fullCorner,
        )
        clipPath(silhouette) {
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = Color(0xFFEAEAEA),
                    topLeft = Offset(0f, top),
                    size = Size(activeWidth, height),
                    cornerRadius = CornerRadius(min(radius, activeWidth * 0.5f)),
                )
                val tickWidth = 2.6.dp.toPx()
                val tickHeight = min(16.dp.toPx(), height - 8.dp.toPx())
                val tickInset = 9.dp.toPx()
                val stride = 10.dp.toPx()
                val count = capsuleLyricsVolumeDashCount(
                    activeWidthPx = activeWidth,
                    firstX = tickInset,
                    dashWidth = tickWidth,
                    stride = stride,
                    endInset = 6.dp.toPx(),
                )
                repeat(count) { index ->
                    drawRoundRect(
                        color = Color(0xFF171717),
                        topLeft = Offset(tickInset + index * stride, top + (height - tickHeight) / 2f),
                        size = Size(tickWidth, tickHeight),
                        cornerRadius = CornerRadius(tickWidth / 2f),
                    )
                }
            }
        }
        // Restrained hairline rather than the bright double-outline on the old control.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(0f, top),
            size = Size(size.width, height),
            cornerRadius = fullCorner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Matched monochrome speaker glyphs. Muted and max share identical bounds and optical weight. */
@Composable
internal fun CapsuleLyricsVolumeIcon(
    muted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape)
            .clickable(onClickLabel = if (muted) "Mute" else "Maximum volume", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.minDimension / 24f
            val ink = Color(0xFFB9B9B9)
            val body = Path().apply {
                moveTo(3.2f * u, 9.4f * u)
                lineTo(7.2f * u, 9.4f * u)
                lineTo(12.2f * u, 5.1f * u)
                lineTo(12.2f * u, 18.9f * u)
                lineTo(7.2f * u, 14.6f * u)
                lineTo(3.2f * u, 14.6f * u)
                close()
            }
            drawPath(body, ink)
            if (muted) {
                drawLine(
                    color = ink,
                    start = Offset(3.1f * u, 4.4f * u),
                    end = Offset(20.2f * u, 20f * u),
                    strokeWidth = 2.4f * u,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color(0xFF171717),
                    start = Offset(4.2f * u, 4.4f * u),
                    end = Offset(21.3f * u, 20f * u),
                    strokeWidth = 0.6f * u,
                    cap = StrokeCap.Round,
                )
            } else {
                drawArc(
                    color = ink, startAngle = -62f, sweepAngle = 124f, useCenter = false,
                    topLeft = Offset(10.2f * u, 6.7f * u),
                    size = Size(7.5f * u, 10.6f * u),
                    style = Stroke(width = 1.8f * u, cap = StrokeCap.Round),
                )
                drawArc(
                    color = ink, startAngle = -63f, sweepAngle = 126f, useCenter = false,
                    topLeft = Offset(9.8f * u, 2.9f * u),
                    size = Size(12f * u, 18.1f * u),
                    style = Stroke(width = 1.7f * u, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Fully visible ticks only; leave a constant small margin at the moving filled edge. */
internal fun capsuleLyricsVolumeDashCount(
    activeWidthPx: Float,
    firstX: Float,
    dashWidth: Float,
    stride: Float,
    endInset: Float,
): Int {
    if (!activeWidthPx.isFinite() || !firstX.isFinite() ||
        !dashWidth.isFinite() || !stride.isFinite() || !endInset.isFinite() ||
        stride <= 0f || dashWidth <= 0f
    ) return 0
    val available = activeWidthPx - endInset - firstX - dashWidth
    if (available < 0f) return 0
    return (floor(available / stride).toInt() + 1).coerceIn(0, 512)
}
