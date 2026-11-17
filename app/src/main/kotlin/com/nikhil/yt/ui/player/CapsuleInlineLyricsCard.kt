/* Capsule MUSIC — a scrollable below-player lyrics surface, not a SIMP Music skin. GPL-3.0. */
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.Lyrics

/**
 * An intentionally quiet, Capsule-owned ink gradient: no bitmap blur, no second animated
 * palette/clock, no grey panel that can flash during track changes. Sampled player colours are
 * mixed into a stable midnight base, making the typography legible even for near-white covers.
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
    visible: Boolean,
    sliderPosition: Long?,
    onLineSeek: (Long) -> Unit,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = capsuleInlineLyricsStops(colors)
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(stops)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.capsule_inline_lyrics_heading),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
                TextButton(onClick = onFullScreen) {
                    Text(
                        stringResource(R.string.capsule_inline_lyrics_expand),
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (visible) {
                    // Reuse Capsule's existing lyric synchronization and interaction, rather
                    // than creating a second parser, polling clock or lyric provider pipeline.
                    Lyrics(
                        sliderPositionProvider = { sliderPosition },
                        isVisible = true,
                        modifier = Modifier.fillMaxSize(),
                        onLineSeek = onLineSeek,
                    )
                } else {
                    Text(
                        stringResource(R.string.capsule_inline_lyrics_hint),
                        modifier = Modifier.align(Alignment.CenterStart).padding(8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.66f),
                    )
                }
            }
        }
    }
}
