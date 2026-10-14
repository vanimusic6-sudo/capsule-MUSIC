/* Capsule MUSIC — stable GPU cover/video composition. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class CapsuleVideoArtworkMotion { SCULPTED, IMMERSIVE }

/**
 * All designs use the same 16:9 output and GPU-clipped shared silhouette.
 * Both children retain FIXED measured sizes. Only graphics layers redraw
 * on each spring frame, so TextureView never gets laid out/resized per frame.
 *
 * Reversing the target while in flight retains the spring's velocity.
 */
@Composable
internal fun CapsuleVideoArtworkTransition(
    presentingVideo: Boolean,
    needsSurface: Boolean,
    modifier: Modifier = Modifier,
    transitionKey: Any? = null,
    fillArtworkSlot: Boolean = false,
    artworkCornerRadius: Dp = 24.dp,
    videoCornerRadius: Dp = 24.dp,
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
    val target = if (presentingVideo) 1f else 0f
    // Two velocity-preserving springs drive the same fixed-sized GPU layer.
    // Cosmo/Minimal get a crisp, controlled handoff. Immersion's leading
    // spring pulls a few pixels against the softer body spring, then both
    // settle organically. Reversals preserve their current velocities.
    val reveal = key(transitionKey) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = if (immersive)
                spring(dampingRatio = 0.83f, stiffness = 82f, visibilityThreshold = 0.001f)
            else
                spring(dampingRatio = 0.88f, stiffness = 145f, visibilityThreshold = 0.001f),
            label = "capsule-video-body",
        )
    }
    // Only Immersion needs a second animator. Other designs read one shared
    // spring, avoiding duplicate invalidations on ordinary player screens.
    val elastic = if (immersive) key(transitionKey) {
        animateFloatAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.67f, stiffness = 130f, visibilityThreshold = 0.001f),
            label = "capsule-video-elastic",
        )
    } else reveal
    // Only mount the native decoder while preparing/revealing/leaving VIDEO.
    // On interrupted closures also wait for the elastic spring to settle.
    val surfaceActive by remember(needsSurface, reveal, elastic) {
        derivedStateOf { needsSurface || reveal.value > 0.004f || elastic.value > 0.004f }
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val slotWidth = constraints.maxWidth.coerceAtLeast(1)
        val slotHeight = constraints.maxHeight.coerceAtLeast(1)
        val coverWidth = if (fillArtworkSlot) slotWidth else min(slotWidth, slotHeight)
        val coverHeight = if (fillArtworkSlot) slotHeight else coverWidth
        val maximumWidth = (windowWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(slotWidth)
        val availableVideoWidth = ((slotWidth * videoWidthScale).roundToInt() -
            with(density) { (videoSidePadding.toPx() * 2).roundToInt() }).coerceIn(1, maximumWidth)
        val videoWidth = min(availableVideoWidth, (slotHeight * 16f / 9f).roundToInt().coerceAtLeast(1))
        val videoHeight = (videoWidth * 9f / 16f).roundToInt().coerceIn(1, slotHeight)
        // A small, fixed overscan is reserved around Immersion so the jelly
        // silhouette cannot be cropped by its own graphics layer at the peak.
        val overscan = if (immersive) with(density) { 8.dp.roundToPx() } else 0
        val frameWidth = max(coverWidth, videoWidth) + overscan * 2
        val frameHeight = max(coverHeight, videoHeight) + overscan * 2
        val frameWidthDp = with(density) { frameWidth.toDp() }
        val frameHeightDp = with(density) { frameHeight.toDp() }
        val coverWidthDp = with(density) { coverWidth.toDp() }
        val coverHeightDp = with(density) { coverHeight.toDp() }
        val videoWidthDp = with(density) { videoWidth.toDp() }
        val videoHeightDp = with(density) { videoHeight.toDp() }
        val coverOffset = with(density) { artworkOffsetY.toPx() }
        val artworkRadius = with(density) { artworkCornerRadius.toPx() }
        val videoRadius = with(density) { videoCornerRadius.toPx() }
        val shadowPx = with(density) { 6.dp.toPx() }

        // The same clamped progress controls clipping, corner radius, alpha
        // and vertical offset: no separate catch-up tween / sudden seam.
        fun widthAt(body: Float, lead: Float): Float =
            if (immersive) {
                val stretch = immersiveJellyStretch(body, lead)
                transitionDimension(coverWidth, videoWidth, smoothVideoStep(body)).toFloat() * (1f + stretch)
            } else {
                transitionDimension(coverWidth, videoWidth, stagedVideoWidth(body)).toFloat()
            }
        fun heightAt(body: Float, lead: Float): Float =
            if (immersive) {
                val stretch = immersiveJellyStretch(body, lead)
                transitionDimension(coverHeight, videoHeight, smoothVideoStep(body)).toFloat() * (1f - stretch * 0.55f)
            } else {
                transitionDimension(coverHeight, videoHeight, stagedVideoHeight(body)).toFloat()
            }
        fun contentHandover(body: Float): Float {
            // The real first frame must already exist (presentingVideo), so
            // keep the cover fully opaque through the initial frame reshape.
            val start = if (immersive) 0.24f else 0.36f
            val length = if (immersive) 0.68f else 0.52f
            return smoothVideoStep((body - start) / length)
        }

        Box(
            Modifier
                .requiredSize(frameWidthDp, frameHeightDp)
                .graphicsLayer {
                    val p = reveal.value.coerceIn(0f, 1f)
                    val w = widthAt(p, elastic.value)
                    val h = heightAt(p, elastic.value)
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f
                    val radius = artworkRadius + (videoRadius - artworkRadius) * smoothVideoStep(p)
                    shape = GenericShape { _, _ ->
                        addRoundRect(RoundRect(left, top, left + w, top + h,
                            CornerRadius(radius.coerceAtLeast(0f))))
                    }
                    clip = true
                    shadowElevation = if (immersive) 0f else shadowPx * smoothVideoStep(p)
                    translationY = coverOffset * (1f - p)
                }
                .drawWithContent {
                    drawContent()
                    if (outline.alpha > 0f) {
                        val p = reveal.value.coerceIn(0f, 1f)
                        val w = widthAt(p, elastic.value)
                        val h = heightAt(p, elastic.value)
                        val radius = artworkRadius + (videoRadius - artworkRadius) * smoothVideoStep(p)
                        drawRoundRect(
                            color = outline,
                            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(radius.coerceAtLeast(0f)),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
                .clickable(enabled = artworkClickable, onClick = onArtworkClick),
            contentAlignment = Alignment.Center,
        ) {
            // The VIDEO decoder is not an artwork placeholder. Without this gate a
            // black 16:9 rectangle was composed before the first cover loaded,
            // even in AUDIO/VIDEO N/A. Keep it mounted throughout preparation
            // and reverse animation, then release it when that phase finishes.
            if (surfaceActive) {
                Box(
                    Modifier.requiredSize(videoWidthDp, videoHeightDp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    video(true)
                }
            }
            Box(
                Modifier
                    .requiredSize(coverWidthDp, coverHeightDp)
                    .graphicsLayer {
                        val p = reveal.value.coerceIn(0f, 1f)
                        alpha = 1f - contentHandover(p)
                        if (immersive) {
                            // Tiny counter-stretch of the cover tracks the outer
                            // clip: a soft liquid pull, never a distorted video
                            // decoder or an AndroidView resized per frame.
                            val jelly = immersiveJellyStretch(p, elastic.value)
                            scaleX = 1f + jelly * 0.36f
                            scaleY = 1f - jelly * 0.20f
                        }
                    }
                    .background(artworkBackground),
                contentAlignment = Alignment.Center,
            ) { artwork() }
        }
    }
}

internal fun transitionDimension(cover: Int, video: Int, progress: Float): Int =
    (cover + (video - cover) * progress.coerceIn(-0.015f, 1.015f)).roundToInt().coerceAtLeast(1)

internal fun smoothVideoStep(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * p * (p * (p * 6f - 15f) + 10f)
}

// Vertical contraction first; width expansion follows. Both reverse together.
internal fun stagedVideoHeight(progress: Float): Float = smoothVideoStep(progress / 0.58f)
internal fun stagedVideoWidth(progress: Float): Float = smoothVideoStep((progress - 0.30f) / 0.70f)

/**
 * A small signed elastic lead drives a 0..2% horizontal pull and compensating
 * vertical squeeze. No distortion remains at either endpoint, even after an
 * in-flight reversal. Only the GPU clip and artwork layers stretch; decoder
 * resolution/aspect ratio and layout never change.
 */
internal fun immersiveJellyStretch(body: Float, lead: Float): Float {
    val middle = 4f * body.coerceIn(0f, 1f) * (1f - body.coerceIn(0f, 1f))
    return ((lead - body) * 0.20f * middle).coerceIn(-0.019f, 0.019f)
}
