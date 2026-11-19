/* Capsule MUSIC — compact read-only lyrics and its shared fullscreen backdrop. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.Lyrics

/**
 * The transition aperture may temporarily freeze its own painted colors for
 * geometric continuity. Once it settles, compact and fullscreen Lyrics consume
 * the exact same already-animated palette as the ordinary player.
 *
 * No second extraction, stale-track holder, delayed grey timer or 1400ms tween.
 */
internal fun capsuleLyricsSharedColors(
    frozenDuringOpening: List<Color>?,
    playerColors: List<Color>,
): List<Color> =
    frozenDuringOpening?.takeIf { it.size >= 3 }
        ?: playerColors.takeIf { it.size >= 3 }
        ?: List(3) { Color(0xFF1A1B21) }

/** One cheap threshold, evaluated only when scrolling crosses the gate. */
internal fun capsuleShouldPrefetchInlineLyrics(scrollPx: Int, viewportPx: Float): Boolean =
    viewportPx.isFinite() && viewportPx > 0f && scrollPx > viewportPx * 0.025f

/**
 * The Immersion cover is a photographic crop with its own sampled 2D field.
 * The regular player's album-palette source can be neutral while this image
 * already paints a richly coloured floor. Derive the compact card from the
 * very same prepared lower edge rather than an unrelated album guess.
 *
 * This is O(1) and allocates no bitmaps or independent image requests.
 */
internal fun capsuleInlineLyricsSceneColors(
    playerColors: List<Color>,
    immersiveTone: ImmersiveArtworkTone?,
): List<Color> {
    val tone = immersiveTone?.takeIf { it.ready } ?: return playerColors
    val field = tone.colorField ?: return listOf(tone.edge, tone.edge, tone.edge)
    if (field.width <= 0 || field.height <= 0)
        return listOf(tone.edge, tone.edge, tone.edge)
    // Immersion's "edge" is the mean of the visible crop and its "accent"
    // is deliberately darkened to 35%. Using those twice in a fully opaque
    // card yielded a flat neutral rectangle despite vibrant artwork above.
    // Instead take three points from the SAME decoded 2D photo field that
    // paints the immersive page, with no separate network request or sampler.
    val centerX = field.width / 2
    val top = field.cell(centerX, (field.height * 0.20f).toInt().coerceAtMost(field.height - 1))
    val middle = field.cell(centerX, (field.height * 0.52f).toInt().coerceAtMost(field.height - 1))
    val lower = field.cell(centerX, (field.height * 0.82f).toInt().coerceAtMost(field.height - 1))
    return listOf(
        lerp(top, tone.edge, 0.20f),
        lerp(middle, tone.edge, 0.25f),
        lerp(lower, tone.edge, 0.35f),
    )
}

/**
 * Keep a stable cover-based luminance from top to bottom.
 * The card and its expanded counterpart paint these exact same stops.
 */
internal fun capsuleInlineLyricsStops(colors: List<Color>): List<Color> {
    val ink = Color(0xFF15161B)
    val upper = colors.getOrNull(0) ?: Color(0xFF1A1B21)
    val lower = colors.getOrNull(1) ?: upper
    val tail = colors.getOrNull(2) ?: lower
    // Keep the real Immersion hue, but protect legibility on near-white,
    // pale-yellow and other very bright covers. Avoid dimming the *bottom*
    // much more than the top: that produces a visible artificial dark band.
    val brightCover = listOf(upper, lower, tail).any { color ->
        maxOf(color.red, color.green, color.blue) > 0.86f
    }
    return if (brightCover) {
        listOf(
            lerp(ink, upper, 0.54f),
            lerp(ink, lower, 0.51f),
            lerp(ink, tail, 0.49f),
        )
    } else {
        // Keep the same hue through the whole card; the former tail factor
        // of 0.34 made even a colourful Immersion cover turn nearly black.
        listOf(
            lerp(ink, upper, 0.76f),
            lerp(ink, lower, 0.67f),
            lerp(ink, tail, 0.58f),
        )
    }
}

/**
 * Exactly one opaque, inexpensive gradient for compact AND full lyrics.
 * Do not render MiniPlayerSurface behind it: its glass/nebula layers were
 * completely covered by this opaque brush, wasting draw/GPU work.
 * Remember the brush across progress ticks and the page reveal.
 */
@Composable
internal fun CapsuleInlineLyricsBackdrop(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    // The regular player already animates its shared artworkScene.colors.
    // Re-animating the same RGB values here for 1400ms made Lyrics lag behind
    // the player and left a gray/old gradient after a series of rapid skips.
    // Use those SAME prepared/animated colors, at the SAME frame, with one
    // inexpensive draw-cache brush and no independent timing coroutine.
    val stops = remember(colors) { capsuleInlineLyricsStops(colors) }
    Box(modifier.drawWithCache {
        val brush = Brush.verticalGradient(stops)
        onDrawBehind { drawRect(brush) }
    })
}

@Composable
internal fun CapsuleInlineLyricsCard(
    colors: List<Color>,
    visible: Boolean,
    sliderPosition: Long?,
    onFullScreen: () -> Unit,
    onBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
    ) {
        CapsuleInlineLyricsBackdrop(
            colors = colors,
            modifier = Modifier.matchParentSize(),
        )
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.capsule_inline_lyrics_heading),
                        color = LocalContentColor.current,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FilledTonalButton(onClick = onFullScreen) {
                        Text(
                            stringResource(R.string.capsule_inline_lyrics_expand),
                            color = LocalContentColor.current,
                            fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (visible) {
                        // Keep the original synced text read-only. There is no
                        // transitional "scroll down" hint to flash on open.
                        Lyrics(
                            sliderPositionProvider = { sliderPosition },
                            isVisible = true,
                            modifier = Modifier.fillMaxSize(),
                            allowUserInteraction = false,
                        )
                    }
                }
            }
        }
    }
}
