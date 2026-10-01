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
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

@Composable
internal fun InstrumentalLyricNote(
    startMs: Long,
    durationMs: Long,
    playbackPosition: State<Long>,
    clockOffsetMs: Long,
    textColor: Color,
    active: Boolean,
    completed: Boolean,
) {
    val painter = painterResource(R.drawable.music_note)
    val tint = remember(textColor) { ColorFilter.tint(textColor) }
    val description = stringResource(R.string.instrumental_pause)
    val scale = animateFloatAsState(
        targetValue = if (active) 1f else 0.96f,
        animationSpec = tween(330, easing = CapsuleStandardEasing),
        label = "instrumentalNoteScale",
    )
    // Reuse the existing lyric clock. Future/completed notes do not subscribe to it; fill
    // updates invalidate drawing only, without rebuilding the lyric list or spawning springs.
    val fill = remember(startMs, durationMs, playbackPosition, clockOffsetMs, active, completed) {
        derivedStateOf(structuralEqualityPolicy()) {
            when {
                active -> instrumentalFillFraction(
                    playbackPosition.value + clockOffsetMs,
                    startMs,
                    durationMs,
                )
                completed -> 1f
                else -> 0f
            }
        }
    }
    Canvas(
        Modifier.size(48.dp)
            .semantics { contentDescription = description }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        with(painter) { draw(size = size, alpha = 0.42f, colorFilter = tint) }
        val fraction = fill.value
        if (fraction > 0f) {
            clipRect(top = size.height * (1f - fraction)) {
                with(painter) { draw(size = size, colorFilter = tint) }
            }
        }
    }
}
