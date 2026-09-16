/**
 * Capsule MUSIC
 * The single sounding lyric line under Capsule Light's artwork card.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * The row keeps its height whether or not the track has synced lyrics.
 * Reserving it is deliberate: the metadata, the progress bar and the transport
 * panel must not jump downwards the moment lyrics finish loading mid-track.
 */
internal val CapsuleLightLyricLineHeight = 34.dp

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
 * Draws [line] and cross-fades to the next one.
 *
 * The animation is driven by the line changing, not by a clock: once a line has
 * settled the row is idle and costs nothing per frame.
 */
@Composable
internal fun CapsuleLightLyricLine(
    line: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().height(CapsuleLightLyricLineHeight),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = line.orEmpty(),
            transitionSpec = {
                (
                    fadeIn(tween(240)) +
                        slideInVertically(tween(280)) { height -> height / 3 }
                ) togetherWith fadeOut(tween(170)) using null
            },
            label = "capsuleLightLyricLine",
        ) { text ->
            androidx.compose.material3.Text(
                text = text,
                color = textColor.copy(alpha = 0.66f),
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
    }
}
