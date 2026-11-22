/*
 * Capsule MUSIC — tactile, hardware-band-aware equalizer graph.
 * Original implementation; GPL-3.0. No animation loop, bitmap capture or polling.
 */
package com.nikhil.yt.ui.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun capsuleEqualizerBandAt(x: Float, width: Float, count: Int, inset: Float): Int {
    if (count < 2 || width <= inset * 2f) return 0
    return (((x - inset) / (width - inset * 2f)).coerceIn(0f, 1f) * (count - 1))
        .roundToInt().coerceIn(0, count - 1)
}

internal fun capsuleEqualizerLevelAt(
    y: Float, height: Float, minMb: Int, maxMb: Int, inset: Float,
): Int {
    if (maxMb <= minMb || height <= 2f * inset) return 0.coerceIn(minMb, maxMb)
    val ratio = ((y - inset) / (height - inset * 2f)).coerceIn(0f, 1f)
    // 50 mB = 0.5 dB. Quantisation keeps the gesture stable at 120 Hz.
    return (maxMb - (maxMb - minMb) * ratio).roundToInt()
        .div(50).times(50).coerceIn(minMb, maxMb)
}

/** Display original Android frequency centers in Hz/kHz, not awkward shorthand. */
internal fun capsuleGraphHz(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1_000 -> {
        val tenths = (hz / 100f).roundToInt()
        val formatted = if (tenths % 10 == 0) "${tenths / 10}"
            else "${tenths / 10}.${tenths % 10}"
        "$formatted kHz"
    }
    else -> "$hz Hz"
}

/**
 * Hardware-aware curve: touching or dragging a point updates only that band.
 * [onFinished] persists the final state; edits between pointer events remain
 * local and do not write DataStore on each rendered frame.
 */
@Composable
internal fun CapsuleEqualizerGraph(
    frequenciesHz: List<Int>,
    levelsMb: List<Int>,
    minMb: Int,
    maxMb: Int,
    selectedBand: Int,
    onBandSelected: (Int) -> Unit,
    onLevelChanged: (Int, Int) -> Unit,
    onFinished: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    graphHeight: Dp = 214.dp,
) {
    val count = minOf(frequenciesHz.size, levelsMb.size)
    if (count <= 0) return
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val updateSelected by rememberUpdatedState(onBandSelected)
    val updateLevel by rememberUpdatedState(onLevelChanged)
    val finish by rememberUpdatedState(onFinished)
    val pad = 18.dp
    val normalized = levelsMb.take(count)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .pointerInput(count, minMb, maxMb) {
                    val inset = pad.toPx()
                    detectTapGestures { point ->
                        val index = capsuleEqualizerBandAt(point.x, size.width.toFloat(), count, inset)
                        updateSelected(index)
                        val level = capsuleEqualizerLevelAt(
                            point.y, size.height.toFloat(), minMb, maxMb, inset,
                        )
                        updateLevel(index, level)
                        finish(index, level)
                    }
                }
                .pointerInput(count, minMb, maxMb) {
                    val inset = pad.toPx()
                    var dragBand = 0
                    var dragLevel = 0
                    detectDragGestures(
                        onDragStart = { point ->
                            dragBand = capsuleEqualizerBandAt(
                                point.x, size.width.toFloat(), count, inset,
                            )
                            updateSelected(dragBand)
                            dragLevel = capsuleEqualizerLevelAt(
                                point.y, size.height.toFloat(), minMb, maxMb, inset,
                            )
                            updateLevel(dragBand, dragLevel)
                        },
                        onDragEnd = { finish(dragBand, dragLevel) },
                        onDragCancel = { finish(dragBand, dragLevel) },
                        onDrag = { change, _ ->
                            dragLevel = capsuleEqualizerLevelAt(
                                change.position.y, size.height.toFloat(), minMb, maxMb, inset,
                            )
                            updateLevel(dragBand, dragLevel)
                            change.consume()
                        },
                    )
                },
        ) {
            val inset = pad.toPx()
            val innerW = (size.width - inset * 2f).coerceAtLeast(1f)
            val innerH = (size.height - inset * 2f).coerceAtLeast(1f)
            val span = (maxMb - minMb).coerceAtLeast(1).toFloat()
            fun point(index: Int): Offset {
                val x = inset + innerW * if (count == 1) 0.5f else index.toFloat() / (count - 1)
                val y = inset + innerH * (1f - (normalized[index] - minMb) / span)
                return Offset(x, y.coerceIn(inset, size.height - inset))
            }
            val baseline = (inset + innerH * (1f - (0 - minMb) / span))
                .coerceIn(inset, size.height - inset)
            // Two subtle helpers, not a harsh table/grid behind every drag.
            for (fraction in listOf(0.25f, 0.75f)) {
                val y = inset + innerH * fraction
                drawLine(grid, Offset(inset, y), Offset(size.width - inset, y), 0.8.dp.toPx())
            }
            drawLine(neutral, Offset(inset, baseline), Offset(size.width - inset, baseline), 1.dp.toPx())
            repeat(count) { i ->
                val x = point(i).x
                drawLine(grid, Offset(x, inset), Offset(x, size.height - inset), 0.75.dp.toPx())
            }

            // Connected shape follows the REAL device bands, not six fake frequencies.
            val curve = Path()
            for (i in 0 until count) {
                val p = point(i)
                if (i == 0) curve.moveTo(p.x, p.y)
                else {
                    val prev = point(i - 1)
                    val midX = (prev.x + p.x) / 2f
                    curve.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                }
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(point(count - 1).x, baseline)
                lineTo(point(0).x, baseline)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.15f), accent.copy(alpha = 0.008f)),
                    startY = inset,
                    endY = size.height,
                ),
            )
            drawPath(curve, color = accent, style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.8.dp.toPx(), cap = StrokeCap.Round,
            ))
            repeat(count) { i ->
                val p = point(i)
                val active = i == selectedBand
                if (active) {
                    drawCircle(accent.copy(alpha = 0.18f), radius = 11.dp.toPx(), center = p)
                }
                drawCircle(
                    color = accent,
                    radius = if (active) 5.5.dp.toPx() else 4.5.dp.toPx(),
                    center = p,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            frequenciesHz.take(count).forEachIndexed { index, hz ->
                Text(
                    text = capsuleGraphHz(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selectedBand) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

