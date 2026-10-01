/*
 * Capsule MUSIC — a bottom-to-top musical-note countdown, inspired by ArchiveTune.
 * GPL-3.0
 */
package com.nikhil.yt.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.lyrics.instrumentalFillFraction
import com.nikhil.yt.lyrics.InstrumentalNotePhase
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

internal val ActiveInstrumentalRowHeight = 64.dp
internal val CompletedInstrumentalRowHeight = 40.dp

@Composable
internal fun InstrumentalLyricNote(
    startMs: Long,
    durationMs: Long,
    playbackPosition: State<Long>,
    clockOffsetMs: Long,
    textColor: Color,
    phase: InstrumentalNotePhase,
) {
    val painter = painterResource(R.drawable.music_note)
    val tint = remember(textColor) { ColorFilter.tint(textColor) }
    val description = stringResource(R.string.instrumental_pause)
    val active = phase == InstrumentalNotePhase.ACTIVE
    val visible = phase != InstrumentalNotePhase.UPCOMING
    val expansion = animateFloatAsState(
        targetValue = when (phase) {
            InstrumentalNotePhase.UPCOMING -> 0f
            InstrumentalNotePhase.ACTIVE -> ActiveInstrumentalRowHeight.value
            InstrumentalNotePhase.COMPLETED -> CompletedInstrumentalRowHeight.value
        },
        animationSpec = tween(if (active) 420 else 320, easing = CapsuleStandardEasing),
        label = "instrumentalRowHeight",
    )
    val opacity = animateFloatAsState(
        targetValue = if (active) 1f else if (visible) 0.64f else 0f,
        animationSpec = tween(330, easing = CapsuleStandardEasing),
        label = "instrumentalNoteOpacity",
    )
    val scale = animateFloatAsState(
        targetValue = if (active) 1f else 0.5f,
        animationSpec = tween(if (active) 420 else 320, easing = CapsuleStandardEasing),
        label = "instrumentalNoteScale",
    )
    val fill = remember(startMs, durationMs, playbackPosition, clockOffsetMs, phase) {
        derivedStateOf(structuralEqualityPolicy()) {
            when (phase) {
                InstrumentalNotePhase.ACTIVE -> instrumentalFillFraction(
                    playbackPosition.value + clockOffsetMs, startMs, durationMs,
                )
                InstrumentalNotePhase.COMPLETED -> 1f
                InstrumentalNotePhase.UPCOMING -> 0f
            }
        }
    }
    Canvas(
        Modifier
            // Measure a constant glyph; only the row's occupied height changes. The neighbours
            // move apart during this finite transition, with no reserved blank row beforehand.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val rowHeight = expansion.value.dp.toPx().roundToInt().coerceAtLeast(0)
                layout(placeable.width, rowHeight) {
                    placeable.placeRelative(0, (rowHeight - placeable.height) / 2)
                }
            }
            .size(48.dp)
            .semantics { if (visible) contentDescription = description }
            .graphicsLayer {
                alpha = opacity.value
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        // Future notes occupy zero height and do no vector drawing.
        if (opacity.value <= 0f) return@Canvas
        with(painter) { draw(size = size, alpha = 0.42f, colorFilter = tint) }
        val fraction = fill.value
        if (fraction > 0f) {
            clipRect(top = size.height * (1f - fraction)) {
                with(painter) { draw(size = size, colorFilter = tint) }
            }
        }
    }
}
