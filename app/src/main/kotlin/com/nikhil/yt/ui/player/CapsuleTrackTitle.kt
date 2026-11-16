/** Capsule MUSIC — whole-glyph manual title, both full player designs. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    // User can drag to read the remainder of the title. It moves by complete character clusters,
    // never shows an ellipsis or clips half of a glyph; after inactivity it returns to the start.
    // This performs no work when idle beyond the normal Text composable.
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val inheritedStyle = LocalTextStyle.current
    val style = remember(inheritedStyle) {
        inheritedStyle.merge(
            TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        )
    }
    val boundaries = remember(title) { capsuleGraphemeBoundaries(title) }
    var firstCluster by androidx.compose.runtime.key(mediaId, title) {
        remember { mutableIntStateOf(0) }
    }
    val onScreen = appIsOnScreen()
    LaunchedEffect(firstCluster, enabled, onScreen, mediaId, title) {
        if (!enabled || !onScreen) {
            firstCluster = 0
        } else if (firstCluster > 0) {
            delay(3_800L)
            firstCluster = 0
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val availablePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(0)
        val lastCluster = boundaries.lastIndex
        val endCluster = remember(title, boundaries, firstCluster, availablePx, style) {
            capsuleTitleWindowEnd(title, boundaries, firstCluster, availablePx) {
                if (it.isEmpty()) 0
                else measurer.measure(text = it, style = style, maxLines = 1, softWrap = false).size.width
            }
        }
        val visibleText = remember(title, boundaries, firstCluster, endCluster) {
            title.substring(boundaries[firstCluster], boundaries[endCluster])
        }
        // A 10dp gesture advances a single Unicode cluster; speed depends only on the user's
        // finger, not a continuously animating marquee or a perpetual Compose scroll state.
        val stepPx = with(density) { 10.dp.toPx() }
        var accumulatedDrag by remember(mediaId, title) { androidx.compose.runtime.mutableFloatStateOf(0f) }
        Text(
            text = visibleText,
            color = color,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled && boundaries.size > 2) {
                    Modifier.pointerInput(title, enabled) {
                        detectHorizontalDragGestures(onDragStart = { accumulatedDrag = 0f }) { change, delta ->
                            change.consume()
                            accumulatedDrag += delta
                            while (accumulatedDrag <= -stepPx && firstCluster < lastCluster - 1) {
                                firstCluster += 1
                                accumulatedDrag += stepPx
                            }
                            while (accumulatedDrag >= stepPx && firstCluster > 0) {
                                firstCluster -= 1
                                accumulatedDrag -= stepPx
                            }
                        }
                    }
                } else Modifier),
        )
    }
}
