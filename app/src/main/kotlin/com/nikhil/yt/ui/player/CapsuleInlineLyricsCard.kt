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
 * Palette shaping utility retained for existing colour regression tests.
 * The visible card uses MiniPlayerSurface with the user's existing mini style.
 */
internal fun capsuleInlineLyricsStops(colors: List<Color>): List<Color> {
    val ink = Color(0xFF0E1420)
    val upper = colors.getOrNull(0) ?: Color(0xFF35506C)
    val lower = colors.getOrNull(1) ?: upper
    return listOf(
        lerp(ink, upper, 0.37f),
        lerp(ink, lower, 0.21f),
        Color(0xFF0A0F18),
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
        sampledGradient.map { it.copy(alpha = 0.84f) }
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
