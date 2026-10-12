/* Capsule MUSIC — stable cover/video composition. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState

/**
 * One moving silhouette, with both contents filling the same bounds.
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
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 260f, visibilityThreshold = 0.001f),
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
        fun frameWidth() = transitionDimension(coverWidth, videoWidth, reveal.value)
        fun frameHeight() = transitionDimension(coverHeight, videoHeight, reveal.value)
        Layout(
            modifier = Modifier
                .graphicsLayer {
                    val corners = reveal.value.coerceIn(0f, 1f)
                    shape = RoundedCornerShape(artworkCornerRadius + (28.dp - artworkCornerRadius) * corners)
                    clip = true
                    translationY = coverOffset * (1f - reveal.value)
                }
                .drawWithContent {
                    drawContent()
                    val corners = reveal.value.coerceIn(0f, 1f)
                    val radius = (artworkCornerRadius + (28.dp - artworkCornerRadius) * corners).toPx()
                    drawRoundRect(outline, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
                }
                .clickable(enabled = artworkClickable, onClick = onArtworkClick),
            content = {
                // Uniform fill follows the moving silhouette. The decoder itself
                // keeps its final viewport, avoiding surface/codec resize churn.
                Box(Modifier.graphicsLayer {
                    alpha = if (!preparing.value && reveal.value > 0f) 1f else 0f
                    val scale = max(frameWidth().toFloat() / videoWidth, frameHeight().toFloat() / videoHeight)
                    scaleX = scale; scaleY = scale
                }.background(Color.Black)) {
                    video(needsSurface)
                }
                // A short content handover inside the single moving frame;
                // no growing hole, duplicate card or full-duration dissolve.
                Box(
                    Modifier.graphicsLayer {
                        val handover = ((reveal.value - 0.28f) / 0.42f).coerceIn(0f, 1f)
                        alpha = if (preparing.value) 1f else 1f - handover * handover * (3f - 2f * handover)
                        val scale = max(frameWidth().toFloat() / coverWidth, frameHeight().toFloat() / coverHeight)
                        scaleX = scale; scaleY = scale
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
    (cover + (video - cover) * progress.coerceIn(-0.015f, 1.015f)).roundToInt().coerceAtLeast(1)
