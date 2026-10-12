/* Capsule MUSIC — stable cover/video composition. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Readiness changes only the frame's bounds and alpha, never the renderer's call site.
 * The native output is measured at its final 16:9 size even while hidden/preparing.
 * The cover keeps its original measurement while the surrounding clip contracts.
 */
@Composable
internal fun CapsuleVideoArtworkTransition(
    presentingVideo: Boolean,
    needsSurface: Boolean,
    modifier: Modifier = Modifier,
    fillArtworkSlot: Boolean = false,
    artworkCornerRadius: Dp = 24.dp,
    artworkOffsetY: Dp = 0.dp,
    videoSidePadding: Dp = 0.dp,
    artworkBackground: Color = Color.Transparent,
    outline: Color = Color.Transparent,
    artworkClickable: Boolean = false,
    onArtworkClick: () -> Unit = {},
    video: @Composable () -> Unit,
    artwork: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val reveal = animateFloatAsState(
        targetValue = if (presentingVideo) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "capsule-cover-video",
    )
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val slotWidth = constraints.maxWidth
        val slotHeight = constraints.maxHeight
        val coverWidth = if (fillArtworkSlot) slotWidth else min(slotWidth, slotHeight)
        val coverHeight = if (fillArtworkSlot) slotHeight else coverWidth
        val availableVideoWidth = (slotWidth - with(density) { (videoSidePadding.toPx() * 2).roundToInt() }).coerceAtLeast(1)
        val videoWidth = min(availableVideoWidth, (slotHeight * 16f / 9f).roundToInt().coerceAtLeast(1))
        val videoHeight = (videoWidth * 9f / 16f).roundToInt().coerceIn(1, slotHeight.coerceAtLeast(1))
        val coverOffset = with(density) { artworkOffsetY.roundToPx() }
        Layout(
            modifier = Modifier
                .graphicsLayer {
                    shape = RoundedCornerShape(artworkCornerRadius + (28.dp - artworkCornerRadius) * reveal.value)
                    clip = true
                    translationY = coverOffset * (1f - reveal.value)
                }
                .clickable(enabled = artworkClickable, onClick = onArtworkClick),
            content = {
                // This wrapper also exists in AUDIO: child indices never depend on readiness.
                Box(Modifier.graphicsLayer { alpha = reveal.value }.background(Color.Black)) {
                    if (needsSurface) video()
                }
                // Keep Coil's image/painter alive at alpha zero; do not remove/reload it at 1f.
                Box(
                    Modifier.graphicsLayer {
                        // A stopped/replaced player has no frame to crossfade from.
                        alpha = if (needsSurface) 1f - reveal.value else 1f
                    }.background(artworkBackground)
                        .border(1.dp, outline, RoundedCornerShape(artworkCornerRadius)),
                    contentAlignment = Alignment.Center,
                ) { artwork() }
            },
        ) { children, _ ->
            val output = children[0].measure(Constraints.fixed(videoWidth, videoHeight))
            val cover = children[1].measure(Constraints.fixed(coverWidth, coverHeight))
            val width = transitionDimension(coverWidth, videoWidth, reveal.value)
            val height = transitionDimension(coverHeight, videoHeight, reveal.value)
            layout(width, height) {
                output.placeRelative((width - output.width) / 2, (height - output.height) / 2)
                cover.placeRelative((width - cover.width) / 2, (height - cover.height) / 2)
            }
        }
    }
}

internal fun transitionDimension(cover: Int, video: Int, progress: Float): Int =
    (cover + (video - cover) * progress.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)
