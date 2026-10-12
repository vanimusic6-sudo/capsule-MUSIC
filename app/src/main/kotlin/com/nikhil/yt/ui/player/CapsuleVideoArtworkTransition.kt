/* Capsule MUSIC — stable cover/video composition. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState

// Zero velocity and acceleration at both ends, without a spring or late corner snap.
internal val CapsuleVideoMorphEasing = Easing { t -> t * t * t * (t * (t * 6f - 15f) + 10f) }

/**
 * Readiness changes only the frame and aperture, never the renderer's call site.
 * The native output is measured at its final 16:9 size even while hidden/preparing.
 * The cover keeps its original measurement while the surrounding clip contracts.
 */
@Composable
internal fun CapsuleVideoArtworkTransition(
    presentingVideo: Boolean,
    needsSurface: Boolean,
    modifier: Modifier = Modifier,
    transitionKey: Any? = null,
    fillArtworkSlot: Boolean = false,
    artworkCornerRadius: Dp = 24.dp,
    artworkOffsetY: Dp = 0.dp,
    videoSidePadding: Dp = 0.dp,
    artworkBackground: Color = Color.Transparent,
    outline: Color = Color.Transparent,
    artworkClickable: Boolean = false,
    onArtworkClick: () -> Unit = {},
    video: @Composable (active: Boolean) -> Unit,
    artwork: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val preparing = rememberUpdatedState(needsSurface && !presentingVideo)
    val reveal = key(transitionKey) { animateFloatAsState(
        targetValue = if (presentingVideo) 1f else 0f,
        animationSpec = tween(if (presentingVideo) 480 else 440, easing = CapsuleVideoMorphEasing),
        label = "capsule-cover-video",
    ) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val slotWidth = constraints.maxWidth
        val slotHeight = constraints.maxHeight
        val coverWidth = if (fillArtworkSlot) slotWidth else min(slotWidth, slotHeight)
        val coverHeight = if (fillArtworkSlot) slotHeight else coverWidth
        val availableVideoWidth = (slotWidth - with(density) { (videoSidePadding.toPx() * 2).roundToInt() }).coerceAtLeast(1)
        val videoWidth = min(availableVideoWidth, (slotHeight * 16f / 9f).roundToInt().coerceAtLeast(1))
        val videoHeight = (videoWidth * 9f / 16f).roundToInt().coerceIn(1, slotHeight.coerceAtLeast(1))
        val coverOffset = with(density) { artworkOffsetY.roundToPx() }
        val aperture = remember { Path() }
        Layout(
            modifier = Modifier
                .graphicsLayer {
                    val corners = 1f - (1f - reveal.value) * (1f - reveal.value)
                    shape = RoundedCornerShape(artworkCornerRadius + (28.dp - artworkCornerRadius) * corners)
                    clip = true
                    translationY = coverOffset * (1f - reveal.value)
                }
                .drawWithContent {
                    drawContent()
                    val corners = 1f - (1f - reveal.value) * (1f - reveal.value)
                    val radius = (artworkCornerRadius + (28.dp - artworkCornerRadius) * corners).toPx()
                    drawRoundRect(outline, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
                }
                .clickable(enabled = artworkClickable, onClick = onArtworkClick),
            content = {
                // No crossfade: the opaque video is exposed by the cover's aperture.
                // In AUDIO this draws only the short-lived exit still, with no decoder surface.
                Box(Modifier.graphicsLayer { alpha = if (reveal.value > 0f) 1f else 0f }.background(Color.Black)) {
                    video(needsSurface)
                }
                // Keep the original opaque cover alive; never blend its colours with VIDEO.
                Box(
                    Modifier.drawWithContent {
                        val progress = if (preparing.value) 0f else reveal.value
                        if (progress <= 0f) {
                            drawContent()
                        } else if (progress < 1f) {
                            val halfWidth = videoWidth * progress / 2f
                            val halfHeight = videoHeight * progress / 2f
                            val radius = 28.dp.toPx() * progress
                            aperture.reset()
                            aperture.addRoundRect(RoundRect(
                                size.width / 2f - halfWidth, size.height / 2f - halfHeight,
                                size.width / 2f + halfWidth, size.height / 2f + halfHeight,
                                CornerRadius(radius),
                            ))
                            clipPath(aperture, ClipOp.Difference) { this@drawWithContent.drawContent() }
                        }
                    }.background(artworkBackground),
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
