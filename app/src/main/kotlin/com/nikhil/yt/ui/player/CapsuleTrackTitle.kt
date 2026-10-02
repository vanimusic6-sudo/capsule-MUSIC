/** Capsule MUSIC — full player title shared by Light, Cosmo and Immersive. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CapsulePlayerArtistFontSize = 17.sp
internal val CapsulePlayerArtistLineHeight = 21.sp

@Composable
internal fun CapsuleTrackTitle(
    mediaId: String,
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var scrollRequest by remember(mediaId, title) { mutableIntStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }

    // Recreate the one-shot marquee for every tap. A continuously looping title would request
    // frames while the player is open even when nobody is reading it.
    key(scrollRequest) {
        Text(
            text = title,
            color = color,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                modifier
                    .clipToBounds()
                    .then(
                        if (scrollRequest > 0 && enabled) {
                            Modifier.basicMarquee(
                                iterations = 1,
                                initialDelayMillis = 0,
                                velocity = 30.dp,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        scrollRequest++
                    },
        )
    }
}
