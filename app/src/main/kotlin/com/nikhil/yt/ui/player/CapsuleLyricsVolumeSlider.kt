/* Capsule MUSIC — a restrained segmented volume control. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A softly rounded capsule with a pale active fill and uniform dark cuts.
 * The number of marks follows the filled width, right up to its rounded moving end.
 * The marks are not separate thumbs or touch targets.
 * No perpetual animation, blur, shader, image, or composition on each drag frame.
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
        val trackHeight = 30.dp.toPx().coerceAtMost(size.height)
        val top = (size.height - trackHeight) * 0.5f
        val bounds = Rect(0f, top, size.width, top + trackHeight)
        // Less rounded than a pill: a deliberate 9dp corner, shared by both segments.
        val radius = min(9.dp.toPx(), trackHeight * 0.5f)
        val corner = CornerRadius(radius)
        val outline = Path().apply { addRoundRect(RoundRect(bounds, corner)) }
        val activeWidth = size.width * volume

        // Draw the complete capsule once. The white active region stays clipped inside its
        // silhouette at every volume (including 0% and 100%).
        drawRoundRect(
            color = Color.White.copy(alpha = 0.065f),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        clipPath(outline) {
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.90f),
                    topLeft = Offset(0f, top),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = CornerRadius(min(radius, activeWidth * 0.5f)),
                )
                val dashWidth = 5.dp.toPx()
                val dashHeight = min(trackHeight - 8.dp.toPx(), 20.dp.toPx()).coerceAtLeast(1f)
                val firstX = 10.dp.toPx()
                val stride = 12.dp.toPx()
                val dashY = top + (trackHeight - dashHeight) * 0.5f
                val count = capsuleLyricsVolumeDashCount(
                    activeWidthPx = activeWidth,
                    firstX = firstX,
                    dashWidth = dashWidth,
                    stride = stride,
                    endInset = 8.dp.toPx(),
                )
                repeat(count) { index ->
                    val x = firstX + index * stride
                    drawRoundRect(
                        color = Color(0xFF111111),
                        topLeft = Offset(x, dashY),
                        size = Size(dashWidth, dashHeight),
                        cornerRadius = CornerRadius(dashWidth * 0.5f),
                    )
                }
            }
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.78f),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
            style = Stroke(width = 1.25.dp.toPx()),
        )
    }
}

/**
 * Geometry only: add each whole dash that fits before the moving fill edge.
 * The old hard-coded repeat(7) left a large blank patch at medium/high volume.
 */
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
    return (kotlin.math.floor(available / stride).toInt() + 1).coerceIn(0, 512)
}
