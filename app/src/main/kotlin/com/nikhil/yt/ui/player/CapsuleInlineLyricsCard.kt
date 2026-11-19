/* Capsule MUSIC — compact read-only lyrics and its shared fullscreen backdrop. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
    // The Immersion page blurs a 2D field and darkens its lower edge.
    // Three VERTICAL points at the centre gave lyrics an unrelated opaque
    // rectangle. Sample one lower-page latitude ACROSS the same artwork.
    // Only three cached cell reads, no bitmap or second image request.
    val row = (field.height * 0.64f).toInt().coerceIn(0, field.height - 1)
    return listOf(
        field.cell((field.width * 0.12f).toInt(), row),
        field.cell(field.width / 2, row),
        field.cell((field.width * 0.88f).toInt(), row),
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
    val rawStops = if (brightCover) {
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

    // A bright album paired with dark accent samples used to produce an
    // unrelated near-black band as the compact card scrolled into view.
    // Limit only an excessive luminance drop, keeping the original shades
    // untouched for dark covers and naturally balanced artwork palettes.
    val minimumLuminance = rawStops.first().luminance() * 0.65f
    return rawStops.mapIndexed { index, stop ->
        if (index == 0 || stop.luminance() >= minimumLuminance) stop
        else {
            var lo = 0f
            var hi = 1f
            repeat(8) {
                val t = (lo + hi) / 2f
                if (lerp(stop, rawStops.first(), t).luminance() < minimumLuminance) lo = t else hi = t
            }
            lerp(stop, rawStops.first(), hi)
        }
    }
}

/**
 * The bottom scanline of the photo-to-floor bitmap is the pixel row actually
 * visible at the end of Immersion's page. This is the only seam-safe source:
 * album palette samples from a different latitude cannot match a blurred
 * and already-darkened background. No new bitmap is allocated or decoded.
 */
internal fun capsuleInlineLyricsPreparedSeamColors(
    prepared: ImmersivePreparedScene?,
    mediaId: String,
): List<Color>? {
    val scene = prepared?.takeIf { it.frame.mediaId == mediaId } ?: return null
    val source = scene.bitmap.asAndroidBitmap()
    if (source.width <= 0 || source.height <= 0) return null
    val y = source.height - 1
    return listOf(
        Color(source.getPixel(0, y)),
        Color(source.getPixel(source.width / 2, y)),
        Color(source.getPixel(source.width - 1, y)),
    )
}

/** Share Immersion's horizontal page continuation across the gap and card. */
internal fun capsuleInlineLyricsImmersiveStops(colors: List<Color>): List<Color> {
    // Paint exactly the same seam colours. The scrollable parent gradually
    // darkens BELOW the seam; dimming these upfront recreated the hard line.
    return colors.takeIf { it.size >= 3 }?.take(3)
        ?: List(3) { Color(0xFF202027) }
}

/** A separate, softly illuminated card on top of the *same* immersive photo.
 *  These are translucent tints, not an independently invented wallpaper. */
internal fun capsuleInlineLyricsImmersivePanelStops(colors: List<Color>): List<Color> {
    val source = colors.getOrNull(1) ?: Color(0xFF262225)
    val ink = Color(0xFF17171B)
    val base = lerp(ink, source, 0.78f)
    val lift = if (maxOf(source.red, source.green, source.blue) > 0.75f) 0.06f else 0.17f
    val top = lerp(base, Color.White, lift)
    val bottom = lerp(base, Color.White, lift * 0.68f)
    return listOf(top.copy(alpha = 0.88f), bottom.copy(alpha = 0.90f))
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
    immersive: Boolean = false,
    immersiveBitmap: ImageBitmap? = null,
) {
    // The regular player already animates its shared artworkScene.colors.
    // Re-animating the same RGB values here for 1400ms made Lyrics lag behind
    // the player and left a gray/old gradient after a series of rapid skips.
    // Use those SAME prepared/animated colors, at the SAME frame, with one
    // inexpensive draw-cache brush and no independent timing coroutine.
    val stops = remember(colors, immersive) {
        if (immersive) capsuleInlineLyricsImmersiveStops(colors)
        else capsuleInlineLyricsStops(colors)
    }
    val panelStops = remember(colors, immersive) {
        if (immersive) capsuleInlineLyricsImmersivePanelStops(colors) else emptyList()
    }
    Box(modifier.drawWithCache {
        val fallback = if (immersive) Brush.horizontalGradient(stops)
            else Brush.verticalGradient(capsuleInlineLyricsStops(colors))
        val panel = if (immersive) Brush.verticalGradient(panelStops) else null
        onDrawBehind {
            if (immersive && immersiveBitmap != null) {
                // Expanded lyrics reveal the same lower photo crop as the
                // compact panel. Its prepared blur is reused: no extra decode.
                val slice = (immersiveBitmap.height * 0.52f).roundToInt()
                    .coerceIn(1, immersiveBitmap.height)
                withTransform({
                    scale(scaleX = 1f, scaleY = -1f,
                        pivot = Offset(size.width / 2f, size.height / 2f))
                }) {
                    drawImage(
                        image = immersiveBitmap,
                        srcOffset = IntOffset(0, immersiveBitmap.height - slice),
                        srcSize = IntSize(immersiveBitmap.width, slice),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(
                            size.width.roundToInt().coerceAtLeast(1),
                            size.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                }
                panel?.let { drawRect(it) }
            } else {
                drawRect(fallback)
            }
        }
    })
}

@Composable
internal fun CapsuleInlineLyricsCard(
    colors: List<Color>,
    visible: Boolean,
    immersive: Boolean = false,
    sliderPosition: Long?,
    onFullScreen: () -> Unit,
    onBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .then(if (immersive) Modifier.border(
                1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(28.dp),
            ) else Modifier)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
    ) {
        if (immersive) {
            // Restore the distinct rounded lyrics card. The reflected photo
            // stays behind it; the panel adds only a translucent tonal lift.
            val panelColors = remember(colors) { capsuleInlineLyricsImmersivePanelStops(colors) }
            Box(Modifier.matchParentSize().drawWithCache {
                val brush = Brush.verticalGradient(panelColors)
                onDrawBehind { drawRect(brush) }
            })
        } else {
            CapsuleInlineLyricsBackdrop(
                colors = colors,
                modifier = Modifier.matchParentSize(),
            )
        }
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
