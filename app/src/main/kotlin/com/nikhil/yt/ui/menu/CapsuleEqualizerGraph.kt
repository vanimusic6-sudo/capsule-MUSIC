/*
 * Capsule MUSIC — tactile, hardware-band-aware equalizer graph.
 * Original implementation; GPL-3.0. No animation loop, bitmap capture or polling.
 */
package com.nikhil.yt.ui.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
    if (minMb > maxMb) return 0
    if (!y.isFinite() || height <= 2f * inset) return 0.coerceIn(minMb, maxMb)
    val ratio = ((y - inset) / (height - inset * 2f)).coerceIn(0f, 1f)
    // 50 mB = 0.5 dB. Quantisation keeps the gesture stable at 120 Hz.
    return (maxMb - (maxMb - minMb) * ratio).roundToInt()
        .div(50).times(50).coerceIn(minMb, maxMb)
}

/** A shared zero line even when bass and treble have different positive ceilings. */
internal fun capsuleGraphZeroY(height: Float, minMb: Int, maxMb: Int, inset: Float): Float {
    val range = (maxMb - minMb).toFloat()
    if (height <= 2f * inset || range <= 0f) return height / 2f
    return inset + (height - 2f * inset) * (maxMb / range).coerceIn(0f, 1f)
}

/** The top of each band reaches its *actual* gain ceiling, not an imaginary higher dB. */
internal fun capsuleBandLevelAt(
    y: Float, height: Float, minMb: Int, maxMb: Int, bandMaxMb: Int, inset: Float,
    bandMinMb: Int = minMb,
): Int {
    if (!y.isFinite() || height <= 2f * inset || minMb >= 0 || maxMb <= 0) {
        return capsuleEqualizerLevelAt(y, height, minMb, maxMb, inset)
            .coerceIn(minOf(bandMinMb, bandMaxMb), maxOf(bandMinMb, bandMaxMb))
    }
    val zero = capsuleGraphZeroY(height, minMb, maxMb, inset)
    val cap = bandMaxMb.coerceIn(0, maxMb)
    val floor = bandMinMb.coerceIn(minMb, 0)
    if (y <= inset) return cap
    if (y >= height - inset) return floor
    val value = if (y <= zero) {
        cap * ((zero - y) / (zero - inset).coerceAtLeast(1f))
    } else {
        floor * ((y - zero) / (height - inset - zero).coerceAtLeast(1f))
    }
    return ((value / 50f).roundToInt() * 50).coerceIn(floor, cap)
}

internal fun capsuleBandGraphY(
    levelMb: Int, height: Float, minMb: Int, maxMb: Int, bandMaxMb: Int, inset: Float,
    bandMinMb: Int = minMb,
): Float {
    val zero = capsuleGraphZeroY(height, minMb, maxMb, inset)
    val cap = bandMaxMb.coerceIn(1, maxMb.coerceAtLeast(1))
    val bottom = height - inset
    return (if (levelMb > 0) {
        zero - (zero - inset) * levelMb.toFloat() / cap
    } else {
        zero + (bottom - zero) * levelMb.toFloat() / bandMinMb.coerceAtMost(-1)
    }).coerceIn(inset, bottom)
}

/** Display original Android frequency centers in Hz/kHz, not awkward shorthand. */
internal fun capsuleGraphShortHz(hz: Int): String = when {
    hz < 1000 -> hz.toString()
    hz % 1000 == 0 -> "${hz / 1000}k"
    else -> {
        val tenths = (hz / 100f).roundToInt()
        "${tenths / 10}.${tenths % 10}k"
    }
}

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
 * [onLevelChanged] previews edits and [onFinished] persists the final state.
 */
@Composable
internal fun CapsuleEqualizerGraph(
    frequenciesHz: List<Int>,
    levelsMb: List<Int>,
    minMb: Int,
    maxMb: Int,
    bandLimitsMb: List<IntRange>,
    selectedBand: Int,
    onBandSelected: (Int) -> Unit,
    onLevelChanged: (Int, Int) -> Unit,
    onFinished: (Int, Int) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
    graphHeight: Dp = 214.dp,
    editable: Boolean = true,
) {
    val count = minOf(frequenciesHz.size, levelsMb.size)
    if (count <= 0) return
    val accent = if (editable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val updateSelected by rememberUpdatedState(onBandSelected)
    val updateLevel by rememberUpdatedState(onLevelChanged)
    val finish by rememberUpdatedState(onFinished)
    val cancel by rememberUpdatedState(onCancelled)
    val pad = 16.dp
    val normalized = levelsMb.take(count)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .then(if (editable) Modifier.pointerInput(count, minMb, maxMb, bandLimitsMb) {
                    val inset = pad.toPx()
                    val side = size.width / (2f * count)
                    // Claim the gesture immediately on the graph. Waiting for
                    // drag touch-slop let the parent verticalScroll steal it,
                    // producing random small jumps while editing EQ points.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val band = capsuleEqualizerBandAt(
                            down.position.x, size.width.toFloat(), count, side,
                        )
                        updateSelected(band)
                        val limits = bandLimitsMb[band]
                        var level = capsuleBandLevelAt(
                            down.position.y, size.height.toFloat(), minMb, maxMb,
                            limits.last, inset, limits.first,
                        )
                        var committed = false
                        try {
                            updateLevel(band, level)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    finish(band, level)
                                    committed = true
                                    break
                                }
                                val next = capsuleBandLevelAt(
                                    change.position.y, size.height.toFloat(), minMb, maxMb,
                                    limits.last, inset, limits.first,
                                )
                                if (next != level) {
                                    level = next
                                    updateLevel(band, level)
                                }
                                change.consume()
                            }
                        } finally {
                            if (!committed) cancel()
                        }
                    }
                } else Modifier)
        ) {
            val inset = pad.toPx()
            val side = size.width / (2f * count)
            val innerW = (size.width - side * 2f).coerceAtLeast(1f)
            val innerH = (size.height - inset * 2f).coerceAtLeast(1f)
            fun point(index: Int): Offset {
                val x = if (count == 1) size.width / 2f else side + innerW * index / (count - 1)
                val y = capsuleBandGraphY(
                    normalized[index], size.height, minMb, maxMb,
                    bandLimitsMb[index].last, inset, bandLimitsMb[index].first,
                )
                return Offset(x, y)
            }
            val baseline = capsuleGraphZeroY(size.height, minMb, maxMb, inset)
            drawLine(neutral, Offset(side, baseline), Offset(size.width - side, baseline), 1.dp.toPx())
            repeat(count) { i ->
                val x = point(i).x
                val rail = 6.dp.toPx()
                drawRoundRect(grid, Offset(x - rail / 2f, inset), Size(rail, innerH), CornerRadius(rail / 2f))
                val top = minOf(point(i).y, baseline)
                val height = maxOf(point(i).y, baseline) - top
                if (height > 0f) drawRoundRect(accent.copy(alpha = 0.19f),
                    Offset(x - rail / 2f, top), Size(rail, height), CornerRadius(rail / 2f))
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
                width = 1.8.dp.toPx(), cap = StrokeCap.Round,
            ))
            repeat(count) { i ->
                val p = point(i)
                val active = i == selectedBand
                val width = (if (active) 25.dp else 18.dp).toPx()
                val height = (if (active) 10.dp else 7.dp).toPx()
                drawRoundRect(accent.copy(alpha = if (active) 1f else 0.76f),
                    Offset(p.x - width / 2f, p.y - height / 2f), Size(width, height), CornerRadius(height / 2f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            frequenciesHz.take(count).forEachIndexed { index, hz ->
                Text(
                    text = if (count >= 8) capsuleGraphShortHz(hz) else capsuleGraphHz(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == selectedBand) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).then(if (editable) Modifier.semantics {
                        val limits = bandLimitsMb[index]
                        progressBarRangeInfo = ProgressBarRangeInfo(normalized[index] / 100f,
                            (limits.first / 100f)..(limits.last / 100f))
                        setProgress { next ->
                            if (next.isFinite()) {
                                updateSelected(index)
                                finish(index, (next * 100).roundToInt().coerceIn(limits))
                                true
                            } else false
                        }
                    } else Modifier),
                )
            }
        }
    }
}

