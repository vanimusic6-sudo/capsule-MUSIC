/* Capsule MUSIC — soft, tactile volume controls in the player family. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * Capsule's volume is not another spotlight. A muted graphite groove sits inside the
 * control card; the lighter part has the same low-contrast, velvet finish as the glyphs.
 * Every tiny tick is identical, and only complete ticks inside the current fill are shown.
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
                fun emit(x: Float) {
                    latestOnValueChange((x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(down.position.x)
                    down.consume()
                    while (true) {
                        val changes = awaitPointerEvent().changes
                        val active = changes.firstOrNull { it.id == down.id } ?: break
                        if (!active.pressed) break
                        emit(active.position.x)
                        active.consume()
                    }
                }
            },
    ) {
        val trackHeight = min(25.dp.toPx(), size.height)
        val top = (size.height - trackHeight) / 2f
        val radius = min(trackHeight / 2f, 10.dp.toPx())
        val corner = CornerRadius(radius)
        val fill = (size.width * volume).coerceIn(0f, size.width)
        val contour = Path().apply {
            addRoundRect(RoundRect(Rect(0f, top, size.width, top + trackHeight), corner))
        }
        // The card underneath is always visible through this matte graphite, with no
        // bright white border, gradient, glass, or pulse animation.
        drawRoundRect(
            color = Color(0xFF46474D).copy(alpha = 0.76f),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        clipPath(contour) {
            if (fill > 0f) {
                drawRoundRect(
                    color = Color(0xFFA9A8B0).copy(alpha = 0.76f),
                    topLeft = Offset(0f, top),
                    size = Size(fill, trackHeight),
                    cornerRadius = CornerRadius(min(radius, fill / 2f)),
                )
                val tickX = 10.dp.toPx()
                val tickWidth = 2.7.dp.toPx()
                val tickStride = 11.dp.toPx()
                val tickHeight = min(14.dp.toPx(), trackHeight - 8.dp.toPx())
                val count = capsuleLyricsVolumeDashCount(
                    activeWidthPx = fill,
                    firstX = tickX,
                    dashWidth = tickWidth,
                    stride = tickStride,
                    endInset = 7.dp.toPx(),
                )
                repeat(count) { index ->
                    drawRoundRect(
                        color = Color(0xFF686970).copy(alpha = 0.74f),
                        topLeft = Offset(
                            tickX + index * tickStride,
                            top + (trackHeight - tickHeight) / 2f,
                        ),
                        size = Size(tickWidth, tickHeight),
                        cornerRadius = CornerRadius(tickWidth / 2f),
                    )
                }
            }
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.11f),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = corner,
            style = Stroke(width = 0.85.dp.toPx()),
        )
    }
}

/** Two hand-drawn sibling icons, same rounded speaker silhouette and touch geometry. */
@Composable
internal fun CapsuleLyricsVolumeIcon(
    muted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(145),
        label = "capsuleVolumeIconPress",
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = if (muted) "Mute volume" else "Maximum volume",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            val unit = size.minDimension / 24f
            val ink = Color(0xFFB0AFB8)
            // A small, gently padded speaker — not the sharp platform glyph.
            val speaker = Path().apply {
                moveTo(3.8f * unit, 9.4f * unit)
                quadraticBezierTo(3.8f * unit, 8.5f * unit, 4.7f * unit, 8.5f * unit)
                lineTo(7.8f * unit, 8.5f * unit)
                lineTo(12.1f * unit, 5.2f * unit)
                quadraticBezierTo(13.6f * unit, 4.1f * unit, 13.6f * unit, 6.3f * unit)
                lineTo(13.6f * unit, 17.7f * unit)
                quadraticBezierTo(13.6f * unit, 19.9f * unit, 12.1f * unit, 18.8f * unit)
                lineTo(7.8f * unit, 15.5f * unit)
                lineTo(4.7f * unit, 15.5f * unit)
                quadraticBezierTo(3.8f * unit, 15.5f * unit, 3.8f * unit, 14.6f * unit)
                close()
            }
            drawPath(speaker, ink)
            if (muted) {
                // A quiet rounded ×, kept beside the speaker rather than slashing it.
                drawLine(
                    color = ink,
                    start = Offset(17.3f * unit, 9.8f * unit),
                    end = Offset(21.4f * unit, 14.2f * unit),
                    strokeWidth = 2.0f * unit,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = Offset(21.4f * unit, 9.8f * unit),
                    end = Offset(17.3f * unit, 14.2f * unit),
                    strokeWidth = 2.0f * unit,
                    cap = StrokeCap.Round,
                )
            } else {
                // Soft nested crescents, both from the same stroke family.
                val near = Path().apply {
                    moveTo(16.1f * unit, 9f * unit)
                    quadraticBezierTo(18.8f * unit, 12f * unit, 16.1f * unit, 15f * unit)
                }
                val far = Path().apply {
                    moveTo(19.0f * unit, 6.9f * unit)
                    quadraticBezierTo(23.4f * unit, 12f * unit, 19.0f * unit, 17.1f * unit)
                }
                drawPath(near, ink, style = Stroke(width = 2.0f * unit, cap = StrokeCap.Round))
                drawPath(far, ink, style = Stroke(width = 1.85f * unit, cap = StrokeCap.Round))
            }
        }
    }
}

/** Return only complete grooves inside the moving fill, up to the last available position. */
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
