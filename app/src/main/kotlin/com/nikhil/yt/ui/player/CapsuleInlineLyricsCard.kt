/* Capsule MUSIC — a player-owned, snap-open lyrics page with miniature Capsule lighting. GPL-3.0. */
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.ui.component.Lyrics

/**
 * Kept for dark/bright-cover contrast checks. The actual embedded page uses the
 * *same selected surface renderer* as the mini-player, not a second lyric palette.
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

/**
 * A whole snap-page, not a vertically scrollable document.
 *
 * During the expansion the page is visual-only. The live, seekable Lyrics tree
 * becomes interactive once the pager has actually settled on its second page.
 * This prevents nested lyrics scrolling from intercepting the revealing swipe.
 */
@Composable
internal fun CapsuleInlineLyricsCard(
    colors: List<Color>,
    miniBackground: MiniPlayerBackgroundStyle,
    pureBlack: Boolean,
    visible: Boolean,
    sliderPosition: Long?,
    onLineSeek: (Long) -> Unit,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        MiniPlayerSurface(
            style = miniBackground,
            pureBlack = pureBlack,
            colors = colors,
            animated = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            // A small contrast scrim is part of this lyric page, while the
            // artwork-derived colour/texture is still the actual mini surface.
            Box(
                Modifier.fillMaxSize().background(
                    Color.Black.copy(alpha =
                        if (miniBackground == MiniPlayerBackgroundStyle.THEME && !pureBlack) 0.20f else 0.12f
                    ),
                ),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onFullScreen,
                            enabled = visible,
                            modifier = Modifier.widthIn(max = 142.dp),
                        ) {
                            Text(
                                stringResource(R.string.capsule_inline_lyrics_expand),
                                color = LocalContentColor.current.copy(alpha = 0.90f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (visible) {
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
                                color = LocalContentColor.current.copy(alpha = 0.72f),
                            )
                        }
                    }
                }
            }
        }
    }
}
