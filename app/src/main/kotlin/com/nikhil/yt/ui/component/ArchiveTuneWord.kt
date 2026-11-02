package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** Paint state only. Typography and word bounds never depend on playback time. */
internal data class ArchiveTuneWordPaint(
    val progress: Float, val scale: Float, val lift: Float,
    val revealAlpha: Float, val glowAlpha: Float, val glowRadius: Float,
    val mask: Boolean,
)

internal fun archiveTuneWordPaint(
    timeMs: Long, startMs: Long, endMs: Long, focus: Float,
    motion: Boolean, completed: Boolean, background: Boolean,
): ArchiveTuneWordPaint {
    val done = completed || timeMs >= endMs
    val active = !completed && timeMs in startMs until endMs
    val progress = when {
        done -> 1f
        timeMs <= startMs -> 0f
        else -> ((timeMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
    }
    val safeFocus = focus.coerceIn(0f, 1f)
    val wave = if (motion && active) sin(progress * Math.PI).toFloat() * safeFocus else 0f
    val glow = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (motion && active) glow * 0.34f * safeFocus else 0f
    return ArchiveTuneWordPaint(
        progress, (0.96f + 0.04f * wave).coerceIn(0.96f, 1f), -3.2f * wave,
        if (safeFocus > 0.001f && (done || active)) safeFocus * if (background) 0.78f else 1f else 0f,
        glowAlpha, if (glowAlpha > 0f) glow * 8f else 0f, active && !done,
    )
}

/** One semantic Text owns the exact font layout. Its cached glyph layout is also
 * painted into the highlight layer; clocks/focus invalidate drawing, never text
 * composition, paragraph shaping, FlowRow measurement or word placement. */
@Composable
internal fun ArchiveTuneWord(
    text: String,
    startTime: Long,
    endTime: Long,
    playbackPosition: State<Long>,
    isRtl: Boolean,
    fontSize: TextUnit,
    textColor: Color,
    isBackground: Boolean,
    lineFocus: State<Float>,
    motionEnabled: Boolean,
    releaseCompleted: Boolean = false,
    modifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val paint = remember(playbackPosition, lineFocus, startTime, endTime, motionEnabled, releaseCompleted, isBackground) {
        derivedStateOf(structuralEqualityPolicy()) {
            archiveTuneWordPaint(
                if (releaseCompleted) Long.MIN_VALUE else playbackPosition.value,
                startTime, endTime, lineFocus.value, motionEnabled, releaseCompleted, isBackground,
            )
        }
    }
    val layout = remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val effectiveFontSize = if (isBackground) fontSize * 0.82f else fontSize
    val baseAlpha = if (isBackground) 0.54f else 0.70f

    Box(modifier.graphicsLayer {
        clip = false
        val frame = paint.value
        translationY = frame.lift
        scaleX = frame.scale
        scaleY = frame.scale
    }) {
        Text(
            text = text, fontSize = effectiveFontSize,
            color = textColor.copy(alpha = baseAlpha), fontWeight = FontWeight.Bold,
            onTextLayout = { layout.value = it; onTextLayout?.invoke(it) },
        )
        Box(Modifier.matchParentSize()
            .graphicsLayer {
                val frame = paint.value
                alpha = if (frame.revealAlpha > 0f) 1f else 0f
                compositingStrategy = if (frame.mask) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithCache {
                val glyphs = layout.value
                val edgeWidth = 10.dp.toPx()
                // Move the mask via the canvas matrix; retain the same shader per word.
                val mask = Brush.horizontalGradient(
                    colors = if (isRtl) listOf(Color.Transparent, Color.Black)
                        else listOf(Color.Black, Color.Transparent),
                    startX = -edgeWidth, endX = edgeWidth,
                )
                onDrawBehind {
                    val frame = paint.value
                    if (glyphs != null && frame.revealAlpha > 0f) {
                        drawText(glyphs, color = textColor.copy(alpha = frame.revealAlpha),
                            shadow = if (frame.glowAlpha > 0f) Shadow(
                                textColor.copy(alpha = frame.glowAlpha), Offset.Zero,
                                frame.glowRadius.coerceAtLeast(1f),
                            ) else Shadow.None)
                        if (frame.mask) {
                            val edge = (size.width + edgeWidth * 2f) * frame.progress - edgeWidth
                            val center = if (isRtl) size.width - edge else edge
                            val bounds = size
                            translate(center, 0f) {
                                drawRect(mask, topLeft = Offset(-center, 0f), size = bounds, blendMode = BlendMode.DstIn)
                            }
                        }
                    }
                }
            })
    }
}
