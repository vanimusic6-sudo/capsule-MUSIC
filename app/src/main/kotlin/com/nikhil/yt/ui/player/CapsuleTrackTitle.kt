/** Capsule MUSIC — whole-glyph manual title, both full player designs. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.BreakIterator
import java.util.Locale

internal val CapsulePlayerArtistFontSize = 17.sp
internal val CapsulePlayerArtistLineHeight = 21.sp

/** Unicode character boundaries, not UTF-16 indices: never split a surrogate or accent. */
internal fun capsuleGraphemeBoundaries(text: String): List<Int> {
    val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
    iterator.setText(text)
    val boundaries = ArrayList<Int>(text.length + 1)
    var at = iterator.first()
    while (at != BreakIterator.DONE) {
        boundaries.add(at)
        at = iterator.next()
    }
    return if (boundaries.isEmpty()) listOf(0) else boundaries
}

/**
 * Find the largest *complete* displayed substring that actually fits. The text is measured with
 * the same font/weight as the rendered Text and leaves a 2px safety margin for antialiasing.
 * Never use Clip on an over-wide title: Clip visibly bisected its last Cyrillic letter.
 */
internal fun capsuleTitleWindowEnd(
    text: String,
    boundaries: List<Int>,
    startCluster: Int,
    widthPx: Int,
    measurePx: (String) -> Int,
): Int {
    val start = startCluster.coerceIn(0, (boundaries.size - 1).coerceAtLeast(0))
    val end = boundaries.lastIndex
    if (start >= end) return end
    val safeWidth = (widthPx - 2).coerceAtLeast(0)
    var lo = start
    var hi = end
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        val candidate = text.substring(boundaries[start], boundaries[mid])
        if (measurePx(candidate) <= safeWidth) lo = mid else hi = mid - 1
    }
    return lo
}

@Composable
internal fun CapsuleTrackTitle(
    mediaId: String,
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A finger must move pixels, not jump from one letter to the next. Restore
    // Compose's native gesture/inertia engine, and apply a whole-grapheme draw clip
    // only AFTER the scroll settles. No idle marquee, no per-letter recomposition.
    val scroll = androidx.compose.runtime.key(mediaId, title) { rememberScrollState() }
    val onScreen = appIsOnScreen()
    val boundaries = remember(title) { capsuleGraphemeBoundaries(title) }
    var layout by remember(mediaId, title) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(mediaId, title, scroll, enabled, onScreen) {
        if (!onScreen) return@LaunchedEffect
        if (!enabled) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        var restoreJob: kotlinx.coroutines.Job? = null
        var returning = false
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (moving) {
                if (!returning) restoreJob?.cancel()
            } else if (!returning && scroll.value > 0) {
                restoreJob?.cancel()
                restoreJob = launch {
                    delay(3_800L)
                    if (!scroll.isScrollInProgress && scroll.value > 0) {
                        returning = true
                        try {
                            scroll.animateScrollTo(0, animationSpec = tween(durationMillis = 620))
                        } finally {
                            returning = false
                        }
                    }
                }
            }
        }
    }

    Text(
        text = title,
        color = color,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout = it },
        modifier = modifier
            .clipToBounds()
            // Outside horizontalScroll: clip in viewport coordinates, not in the
            // full intrinsic text width. Draw-time only, no relayout or new layer.
            .drawWithContent {
                val measured = layout
                if (measured == null || scroll.isScrollInProgress || boundaries.size < 2) {
                    drawContent()
                } else {
                    val shift = scroll.value.toFloat()
                    var left = size.width
                    var right = 0f
                    for (i in 0 until boundaries.lastIndex) {
                        val start = boundaries[i]
                        val end = boundaries[i + 1]
                        if (end <= start) continue
                        val startX = measured.getBoundingBox(start).left - shift
                        val endX = measured.getBoundingBox(end - 1).right - shift
                        if (startX >= -0.5f && endX <= size.width - 2f) {
                            left = minOf(left, startX.coerceAtLeast(0f))
                            right = maxOf(right, endX)
                        }
                    }
                    if (right > left) {
                        clipRect(left = left, right = right.coerceAtMost(size.width)) {
                            // clipRect has DrawScope, while drawContent belongs to the
                            // surrounding ContentDrawScope; use its explicit receiver.
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            }
            .horizontalScroll(scroll, enabled = enabled),
    )
}
