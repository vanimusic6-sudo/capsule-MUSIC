/**
 * Capsule MUSIC
 * The single sounding lyric line under Capsule Light's artwork card.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.nikhil.yt.lyrics.LyricsEntry
import com.nikhil.yt.lyrics.LyricsUtils
import com.nikhil.yt.utils.reportException

/**
 * The height the row holds even with nothing in it.
 *
 * Reserving one line is deliberate: the metadata, the progress bar and the
 * transport panel must not jump downwards the moment lyrics finish loading
 * mid-track. A line too long for the width wraps to a second one and the row
 * grows for as long as that line is sounding — wrapping downwards is the point,
 * and the alternative, reserving two lines for every track, would keep
 * everything below pushed down for a line that usually fits in one.
 */
internal val CapsuleLightLyricLineHeight = 24.dp

/** A long line wraps once. Beyond that it is ellipsised rather than taking the screen. */
private const val MAX_LINES = 2

private const val LINE_ALPHA = 0.66f
private const val FADE_OUT_MILLIS = 150
private const val FADE_IN_MILLIS = 240

/** A few pixels of rise as the line arrives. Small enough not to need a dp, cheap as a matrix. */
private const val RISE_PX = 10f

/** The line is shown this long before it is due, matching the full lyrics screen. */
private const val LINE_LEAD_MS = 300L

/**
 * Timed lines for [lyrics], or an empty list when the track has none.
 *
 * Only synced formats are followed. Plain lyrics have no timing, so there is no
 * honest way to pick "the line that is sounding now" from them.
 */
internal fun capsuleLightLyricLines(lyrics: String?): List<LyricsEntry> {
    val raw = lyrics?.trim().orEmpty()
    if (raw.isEmpty() || raw == LYRICS_NOT_FOUND) return emptyList()
    return try {
        when {
            LyricsUtils.isTtml(raw) -> LyricsUtils.parseTtml(raw)
            raw.startsWith("[") -> LyricsUtils.parseLyrics(raw)
            else -> emptyList()
        }
    } catch (e: Exception) {
        reportException(e)
        emptyList()
    }
}

/**
 * The line sounding at [positionMs], or null before the first line starts and
 * during instrumental gaps, where the row stays empty rather than holding a
 * line that is no longer being sung.
 */
internal fun capsuleLightLyricLineAt(
    lines: List<LyricsEntry>,
    positionMs: Long,
): String? {
    if (lines.isEmpty()) return null
    val position = positionMs.coerceAtLeast(0L)
    val index = LyricsUtils.findCurrentLineIndex(lines, position, leadMs = LINE_LEAD_MS)
    val entry = lines.getOrNull(index) ?: return null
    if (position + LINE_LEAD_MS < entry.time) return null
    return entry.text.trim().replace('\n', ' ').takeIf { it.isNotEmpty() }
}

/**
 * Draws [line], fading to the next one as playback moves.
 *
 * Deliberately not `AnimatedContent`. That composes both lines at once for the length of the
 * change and animates each through `alpha`, and a layer with an alpha below 1 has to be composited
 * through an offscreen buffer — a buffer allocated and blended every frame of every line change,
 * for one line of text. Here the fade is the text colour's own alpha, which is a paint value the
 * glyphs are drawn with, and the small rise is a translation on a layer that stays fully opaque.
 * One text node, no buffer, same result on screen.
 *
 * It is driven by the line changing, not by a clock: once a line has settled nothing is running.
 */
@Composable
internal fun CapsuleLightLyricLine(
    line: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val settled = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(line.orEmpty()) }

    LaunchedEffect(line) {
        val next = line.orEmpty()
        if (next == shown) return@LaunchedEffect
        if (shown.isNotEmpty()) settled.animateTo(0f, tween(FADE_OUT_MILLIS))
        shown = next
        // Nothing to fade in during an instrumental gap: the row is simply empty.
        if (next.isEmpty()) return@LaunchedEffect
        settled.snapTo(0f)
        settled.animateTo(1f, tween(FADE_IN_MILLIS))
    }

    val arrived = settled.value.coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = CapsuleLightLyricLineHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = shown,
            color = textColor.copy(alpha = LINE_ALPHA * arrived),
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            maxLines = MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = (1f - arrived) * RISE_PX },
        )
    }
}
