/**
 * Capsule MUSIC
 * The single sounding lyric line under Capsule Light's artwork card.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.nikhil.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.nikhil.yt.lyrics.LyricsEntry
import com.nikhil.yt.lyrics.withInstrumentalBreaks
import com.nikhil.yt.lyrics.findInstrumentalAwareLineIndex
import com.nikhil.yt.lyrics.isInstrumentalInterval
import com.nikhil.yt.lyrics.instrumentalFillFraction
import com.nikhil.yt.ui.component.InstrumentalNoteGlyph
import com.nikhil.yt.lyrics.LyricsUtils
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.ui.motion.CapsuleEnterEasing

/**
 * The height the row always holds: two lines' worth, whether it has two or none.
 *
 * Reserving the full wrap is what keeps the layout still. One line was enough
 * for the common case, but a long line then grew the row and pushed the title,
 * the progress bar and the transport panel down for as long as it was sounding,
 * so the controls moved under the thumb every few seconds. Holding the space up
 * front costs a little height once instead of moving everything repeatedly.
 *
 * Two lines of LINE_SIZE at LINE_HEIGHT come to 38dp; the rest is breathing room.
 */
internal val CapsuleLightLyricLineHeight = 44.dp

/** A long line wraps once. Beyond that it is ellipsised rather than taking the screen. */
private const val MAX_LINES = 2

private const val LINE_ALPHA = 0.66f
private val LINE_SIZE = 15.sp
private val LINE_HEIGHT = 19.sp

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
internal fun capsuleLightLyricLines(lyrics: String?, durationMs: Long = 0L): List<LyricsEntry> {
    val raw = lyrics?.trim().orEmpty()
    if (raw.isEmpty() || raw == LYRICS_NOT_FOUND) return emptyList()
    return try {
        val parsed = when {
            LyricsUtils.isTtml(raw) -> LyricsUtils.parseTtml(raw)
            raw.startsWith("[") -> LyricsUtils.parseLyrics(raw)
            else -> emptyList()
        }
        withInstrumentalBreaks(parsed, durationMs.coerceAtLeast(0L))
    } catch (e: Exception) {
        reportException(e)
        emptyList()
    }
}

/** Use exactly the same pause intervals and vocal anticipation as the full lyrics screen. */
internal fun capsuleLightLyricEntryAt(lines: List<LyricsEntry>, positionMs: Long): LyricsEntry? {
    if (lines.isEmpty()) return null
    val position = positionMs.coerceAtLeast(0L)
    val index = findInstrumentalAwareLineIndex(lines, position, leadMs = LINE_LEAD_MS)
    val entry = lines.getOrNull(index) ?: return null
    return if (entry.isInstrumental) {
        entry.takeIf { isInstrumentalInterval(position, it.time, it.durationMs) }
    } else {
        entry.takeIf { position + LINE_LEAD_MS >= it.time && it.text.isNotBlank() }
    }
}

internal fun capsuleLightLyricLineAt(lines: List<LyricsEntry>, positionMs: Long): String? =
    capsuleLightLyricEntryAt(lines, positionMs)?.takeUnless { it.isInstrumental }
        ?.text?.trim()?.replace('\n', ' ')

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
    instrumental: LyricsEntry? = null,
    playbackPosition: State<Long> = rememberUpdatedState(0L),
    animate: Boolean = true,
    trackKey: String = "",
) {
    // Track-keyed state prevents the previous song's lyrics flashing under a new cover.
    // New text and outgoing text share ONE short clock, not sequential fade-out/fade-in
    // with a blank interval. Alpha is painted into glyph colours, not a GPU buffer.
    val transition = remember(trackKey) { Animatable(1f) }
    var shown by remember(trackKey) { mutableStateOf("") }
    var outgoing by remember(trackKey) { mutableStateOf("") }

    LaunchedEffect(trackKey, line, animate) {
        val next = line.orEmpty()
        if (!animate) {
            shown = next
            outgoing = ""
            transition.snapTo(1f)
            return@LaunchedEffect
        }
        if (next == shown) return@LaunchedEffect

        outgoing = shown
        shown = next
        transition.snapTo(0f)
        transition.animateTo(1f, tween(280, easing = CapsuleEnterEasing))
        outgoing = ""
    }

    val arrived = transition.value.coerceIn(0f, 1f)
    val reservedHeight = maxOf(
        CapsuleLightLyricLineHeight,
        with(LocalDensity.current) { LINE_HEIGHT.toDp() * MAX_LINES + 6.dp },
    )
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = reservedHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (instrumental != null) {
            // Follow the existing playback clock and preserve the quiet note animation.
            val fill = animateFloatAsState(
                targetValue = instrumentalFillFraction(
                    playbackPosition.value, instrumental.time, instrumental.durationMs,
                ),
                animationSpec = tween(if (animate) 300 else 0, easing = LinearEasing),
                label = "lightInstrumentalFill",
            )
            InstrumentalNoteGlyph(
                fill = { fill.value },
                textColor = textColor.copy(alpha = LINE_ALPHA * (0.7f + 0.3f * arrived)),
                modifier = Modifier.size(30.dp),
            )
        } else {
            if (outgoing.isNotEmpty() && arrived < 1f) {
                Text(
                    text = outgoing,
                    color = textColor.copy(alpha = LINE_ALPHA * (1f - arrived)),
                    fontSize = LINE_SIZE,
                    lineHeight = LINE_HEIGHT,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    maxLines = MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationY = -RISE_PX * 0.55f * arrived
                    },
                )
            }
            if (shown.isNotEmpty()) {
                Text(
                    text = shown,
                    color = textColor.copy(alpha = LINE_ALPHA * arrived),
                    fontSize = LINE_SIZE,
                    lineHeight = LINE_HEIGHT,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    maxLines = MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationY = RISE_PX * 0.65f * (1f - arrived)
                    },
                )
            }
        }
    }
}
