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
import com.nikhil.yt.lyrics.isInstrumentalInterval
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

@Composable
internal fun InstrumentalLyricNote(
    startMs: Long,
    durationMs: Long,
    playbackPosition: State<Long>,
    clockOffsetMs: Long,
    textColor: Color,
    trackPlayback: Boolean,
) {
    val painter = painterResource(R.drawable.music_note)
    val tint = remember(textColor) { ColorFilter.tint(textColor) }
    val description = stringResource(R.string.instrumental_pause)
    val inPause = remember(startMs, durationMs, playbackPosition, clockOffsetMs, trackPlayback) {
        derivedStateOf(structuralEqualityPolicy()) {
            trackPlayback && isInstrumentalInterval(
                playbackPosition.value + clockOffsetMs, startMs, durationMs,
            )
        }
    }
    // Only interval boundaries recompose this row. Filling is a drawing-only clock read.
    val visible = inPause.value
    val opacity = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 330 else 250, easing = CapsuleStandardEasing),
        label = "instrumentalNoteOpacity",
    )
    val scale = animateFloatAsState(
        targetValue = if (visible) 1f else 0.96f,
        animationSpec = tween(330, easing = CapsuleStandardEasing),
        label = "instrumentalNoteScale",
    )
    val fill = remember(startMs, durationMs, playbackPosition, clockOffsetMs, trackPlayback) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (trackPlayback) instrumentalFillFraction(
                playbackPosition.value + clockOffsetMs, startMs, durationMs,
            ) else 0f
        }
    }
    Canvas(
        Modifier.size(48.dp)
            .semantics { if (visible) contentDescription = description }
            .graphicsLayer {
                alpha = opacity.value
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        // Keep row geometry stable for auto-scroll, but do no vector drawing when hidden.
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
