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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.key

internal enum class CapsuleVideoArtworkMotion { SCULPTED, IMMERSIVE }

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
    videoWidthScale: Float = 1f,
    motion: CapsuleVideoArtworkMotion = CapsuleVideoArtworkMotion.SCULPTED,
    artworkBackground: Color = Color.Transparent,
    outline: Color = Color.Transparent,
    artworkClickable: Boolean = false,
    onArtworkClick: () -> Unit = {},
    video: @Composable (active: Boolean) -> Unit,
    artwork: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val immersive = motion == CapsuleVideoArtworkMotion.IMMERSIVE
    val reveal = key(transitionKey) { animateFloatAsState(
        targetValue = if (presentingVideo) 1f else 0f,
        animationSpec = if (immersive) spring(dampingRatio = 1f, stiffness = 56f, visibilityThreshold = 0.001f)
            else spring(dampingRatio = 0.92f, stiffness = 105f, visibilityThreshold = 0.001f),
        label = "capsule-cover-video",
    ) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val slotWidth = constraints.maxWidth
        val slotHeight = constraints.maxHeight
        val coverWidth = if (fillArtworkSlot) slotWidth else min(slotWidth, slotHeight)
        val coverHeight = if (fillArtworkSlot) slotHeight else coverWidth
        val maximumWidth = (windowWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(slotWidth)
        val availableVideoWidth = ((slotWidth * videoWidthScale).roundToInt() - with(density) { (videoSidePadding.toPx() * 2).roundToInt() }).coerceIn(1, maximumWidth)
        val videoWidth = min(availableVideoWidth, (slotHeight * 16f / 9f).roundToInt().coerceAtLeast(1))
        val videoHeight = (videoWidth * 9f / 16f).roundToInt().coerceIn(1, slotHeight.coerceAtLeast(1))
        val coverOffset = with(density) { artworkOffsetY.roundToPx() }
        fun frameWidth() = if (immersive) transitionDimension(coverWidth, videoWidth, reveal.value)
            else transitionDimension(coverWidth, videoWidth, stagedVideoWidth(reveal.value))
        fun frameHeight() = if (immersive) transitionDimension(coverHeight, videoHeight, reveal.value)
            else transitionDimension(coverHeight, videoHeight, stagedVideoHeight(reveal.value))
        // One reversible phase drives the geometry and both content layers.
        fun contentHandover(): Float {
            val start = if (immersive) 0.22f else 0.40f
            val duration = if (immersive) 0.66f else 0.48f
            return smoothVideoStep((reveal.value - start) / duration)
        }
        Layout(
            modifier = Modifier
                .graphicsLayer {
                    // The mask is one physical piece; its corner radius never catches up later.
                    shape = RoundedCornerShape(artworkCornerRadius)
                    clip = true
                    translationY = coverOffset * (1f - reveal.value)
                }
                .drawWithContent {
                    drawContent()
                    val radius = artworkCornerRadius.toPx()
                    if (outline.alpha > 0f) drawRoundRect(outline, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx()))
                }
                .clickable(enabled = artworkClickable, onClick = onArtworkClick),
            content = {
                // Uniform fill follows the moving silhouette. The decoder itself
                // keeps its final viewport, avoiding surface/codec resize churn.
                Box(Modifier.graphicsLayer {
                    val scale = max(frameWidth().toFloat() / videoWidth, frameHeight().toFloat() / videoHeight)
                    scaleX = scale; scaleY = scale
                }.background(Color.Black)) {
                    // Retain the TextureView through the reverse morph. The service
                    // has already captured its exit still before stopping playback.
                    video(needsSurface || reveal.value > 0.005f)
                }
                // A short content handover inside the single moving frame;
                // no growing hole, duplicate card or full-duration dissolve.
                Box(
                    Modifier.graphicsLayer {
                        val p = reveal.value.coerceIn(0f, 1f)
                        // The whole cover flows within one rectangular silhouette.
                        // The same phase runs backwards on close, and spring
                        // velocity survives an interrupted/reversed gesture.
                        alpha = 1f - contentHandover()
                        val scale = max(frameWidth().toFloat() / coverWidth, frameHeight().toFloat() / coverHeight) *
                            (1f + if (immersive) 0.008f * 4f * p * (1f - p) else 0f)
                        scaleX = scale; scaleY = scale
                    }.background(artworkBackground),
                    contentAlignment = Alignment.Center,
                ) { artwork() }
            },
        ) { children, _ ->
            val output = children[0].measure(Constraints.fixed(videoWidth, videoHeight))
            val cover = children[1].measure(Constraints.fixed(coverWidth, coverHeight))
            val width = frameWidth()
            val height = frameHeight()
            layout(width, height) {
                output.placeRelative((width - output.width) / 2, (height - output.height) / 2)
                cover.placeRelative((width - cover.width) / 2, (height - cover.height) / 2)
            }
        }
    }
}

internal fun transitionDimension(cover: Int, video: Int, progress: Float): Int =
    (cover + (video - cover) * progress.coerceIn(-0.015f, 1.015f)).roundToInt().coerceAtLeast(1)

internal fun smoothVideoStep(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * p * (p * (p * 6f - 15f) + 10f)
}

// On opening, contract the cover vertically before widening it into a 16:9 window.
// Reversing the same progress naturally contracts width first and restores height last.
internal fun stagedVideoHeight(progress: Float): Float = smoothVideoStep(progress / 0.58f)
internal fun stagedVideoWidth(progress: Float): Float = smoothVideoStep((progress - 0.30f) / 0.70f)
