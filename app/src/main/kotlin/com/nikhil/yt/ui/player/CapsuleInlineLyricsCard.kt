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
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Keep a stable cover-based luminance from top to bottom.
 * The card and its expanded counterpart paint these exact same stops.
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
    val brush = remember(colors) { Brush.verticalGradient(capsuleInlineLyricsStops(colors)) }
    Box(modifier.background(brush))
}

@Composable
internal fun CapsuleInlineLyricsCard(
    colors: List<Color>,
    visible: Boolean,
    sliderPosition: Long?,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(28.dp)),
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
