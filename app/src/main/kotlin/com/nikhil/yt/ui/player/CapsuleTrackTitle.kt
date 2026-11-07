/** Capsule MUSIC — manual, single-line player title for all three player designs. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

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
    // Never auto-marquee. A finger can drag the title to reveal hidden letters;
    // a new song resets the position, and inactivity gently restores the start.
    val scroll = androidx.compose.runtime.key(mediaId, title) { rememberScrollState() }
    LaunchedEffect(mediaId, title, scroll, enabled) {
        if (!enabled) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        snapshotFlow { scroll.isScrollInProgress }.collectLatest { moving ->
            if (!moving && scroll.value > 0) {
                delay(3_800L)
                if (!scroll.isScrollInProgress && scroll.value > 0) {
                    scroll.animateScrollTo(0, animationSpec = tween(durationMillis = 620))
                }
            }
        }
    }
    Text(
        text = title,
        color = color,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.clipToBounds().horizontalScroll(scroll, enabled = enabled),
    )
}
