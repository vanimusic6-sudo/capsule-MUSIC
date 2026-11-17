/* Capsule MUSIC — familiar scroll-to-mini-lyrics page, with a read-only preview. GPL-3.0. */
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.Lyrics

/**
 * Keep the compact lyric card at a consistent luminance while the document
 * scrolls over it. A former hard-coded near-black bottom stop made the card
 * appear to dim continuously as more of its lower half became visible.
 * All three tones now come from the CURRENT cover and share a muted anchor.
 */
internal fun capsuleInlineLyricsStops(colors: List<Color>): List<Color> {
    val ink = Color(0xFF1B2028)
    val upper = colors.getOrNull(0) ?: Color(0xFF35506C)
    val lower = colors.getOrNull(1) ?: upper
    val tail = colors.getOrNull(2) ?: lower
    val anchor = lerp(ink, upper, 0.32f)
    return listOf(
        anchor,
        lerp(anchor, lower, 0.12f),
        lerp(anchor, tail, 0.14f),
    )
}

@Composable
internal fun CapsuleInlineLyricsCard(
    colors: List<Color>,
    miniBackground: MiniPlayerBackgroundStyle,
    pureBlack: Boolean,
    visible: Boolean,
    sliderPosition: Long?,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The compact lyrics card is still the original mini-style surface,
    // but its artwork gradient must be painted independently of the user's
    // mini-player theme (THEME otherwise left this card flat).
    val sampledGradient = capsuleInlineLyricsStops(colors)
    val lightThemeSurface = miniBackground == MiniPlayerBackgroundStyle.THEME &&
        !pureBlack && MaterialTheme.colorScheme.surface.luminance() > 0.55f
    val cardGradient = if (lightThemeSurface) {
        sampledGradient.map { lerp(MaterialTheme.colorScheme.surface, it, 0.20f) }
    } else {
        // Opaque stops prevent the independently shaded mini surface beneath
        // this card from adding a second, scroll-revealed dark gradient.
        sampledGradient
    }
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp)),
    ) {
        MiniPlayerSurface(
            style = miniBackground,
            pureBlack = pureBlack,
            colors = colors,
            modifier = Modifier.fillMaxSize(),
            animated = false,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(cardGradient))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
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
                    // The ordinary Lyrics pipeline stays synchronized, but this
                    // preview cannot scroll, select words or seek playback.
                    Lyrics(
                        sliderPositionProvider = { sliderPosition },
                        isVisible = true,
                        modifier = Modifier.fillMaxSize(),
                        allowUserInteraction = false,
                    )
                } else {
                    Text(
                        stringResource(R.string.capsule_inline_lyrics_hint),
                        modifier = Modifier.align(Alignment.CenterStart).padding(8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalContentColor.current.copy(alpha = 0.66f),
                    )
                }
            }
        }
        }
    }
}
