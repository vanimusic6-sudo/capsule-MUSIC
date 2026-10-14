/* Capsule MUSIC — stable GPU cover/video composition. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

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
    // Animatable exposes isRunning: a video surface cannot be destroyed for
    // one frame when an underdamped closing spring crosses zero and rebounds.
    // animateTo() maintains velocity on target reversal (including mid-flight).
    // Each mediaId gets its own starting position without carry-over.
    val body = remember(transitionKey) { Animatable(target) }
    LaunchedEffect(body, target, immersive) {
        // An initial AUDIO placeholder already sits at zero. Starting a
        // no-op spring there can momentarily mark isRunning=true and mount
        // the black video surface before the cover image is ready.
        if (abs(body.value - target) > 0.0001f || abs(body.velocity) > 0.0001f) {
            body.animateTo(
                targetValue = target,
                animationSpec = if (immersive)
                    spring(dampingRatio = 0.77f, stiffness = 105f, visibilityThreshold = 0.001f)
                else
                    spring(dampingRatio = 0.86f, stiffness = 210f, visibilityThreshold = 0.001f),
            )
        }
    }
    val lead = if (immersive) remember(transitionKey) { Animatable(target) } else body
    if (immersive) {
        LaunchedEffect(lead, target) {
            if (abs(lead.value - target) > 0.0001f || abs(lead.velocity) > 0.0001f) {
                lead.animateTo(
                    targetValue = target,
                    animationSpec = spring(dampingRatio = 0.63f, stiffness = 270f, visibilityThreshold = 0.001f),
                )
            }
        }
    }
    // Render lease must not flicker during a negative spring overshoot.
    // Derived state only changes composition at the mount/unmount boundary.
    val surfaceActive by remember(needsSurface, body, lead) {
        derivedStateOf {
            needsSurface || body.isRunning || lead.isRunning ||
                abs(body.value) > 0.001f || abs(lead.value) > 0.001f
        }
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

        // Geometry is one shared bounds transformation. Sequential vertical
        // then horizontal resize in the old implementation felt like two
        // separate snapping animations, not one attached material surface.
        // The renderer is NEVER resized; only this contour follows the spring.
        fun widthAt(p: Float, leading: Float): Float {
            val base = coverWidth.toFloat() +
                (videoWidth - coverWidth).toFloat() * smoothVideoStep(p)
            return if (immersive) base * (1f + immersiveJellyStretch(p, leading)) else base
        }
        fun heightAt(p: Float, leading: Float): Float {
            val base = coverHeight.toFloat() +
                (videoHeight - coverHeight).toFloat() * smoothVideoStep(p)
            return if (immersive) base * (1f - immersiveJellyStretch(p, leading) * 0.55f) else base
        }
        fun contentHandover(p: Float): Float {
            // One optical handover, after VIDEO has its first frame. Immersion
            // remains visible long enough to carry the user's cover identity.
            val start = if (immersive) 0.23f else 0.29f
            val length = if (immersive) 0.77f else 0.60f
            return smoothVideoStep((p - start) / length)
        }

        Box(
            Modifier
                .requiredSize(frameWidthDp, frameHeightDp)
                .graphicsLayer {
                    val p = body.value
                    val leading = lead.value
                    val w = widthAt(p, leading)
                    val h = heightAt(p, leading)
                    val adhesion = immersiveEdgeTension(p, leading)
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f +
                        if (immersive) adhesion * with(density) { 9.dp.toPx() } else 0f
                    val radius = (artworkRadius + (videoRadius - artworkRadius) *
                        smoothVideoStep(p)).coerceIn(0f, min(w, h) / 2f)
                    if (immersive) {
                        // Unlike uniform scale, the top and bottom edge lag the
                        // faster spring. Subtle cubic tension creates a real
                        // soft-surface silhouette, including reverse recoil.
                        val pullPx = with(density) { 6.dp.toPx() } * adhesion
                        shape = GenericShape { _, _ ->
                            val right = left + w
                            val bottom = top + h
                            val inner = (w - radius * 2f).coerceAtLeast(0f)
                            moveTo(left + radius, top)
                            cubicTo(
                                left + radius + inner * 0.30f, top - pullPx,
                                left + radius + inner * 0.70f, top - pullPx,
                                right - radius, top,
                            )
                            quadraticBezierTo(right, top, right, top + radius)
                            lineTo(right, bottom - radius)
                            quadraticBezierTo(right, bottom, right - radius, bottom)
                            cubicTo(
                                right - radius - inner * 0.30f, bottom - pullPx * 0.65f,
                                right - radius - inner * 0.70f, bottom - pullPx * 0.65f,
                                left + radius, bottom,
                            )
                            quadraticBezierTo(left, bottom, left, bottom - radius)
                            lineTo(left, top + radius)
                            quadraticBezierTo(left, top, left + radius, top)
                            close()
                        }
                    } else {
                        shape = GenericShape { _, _ ->
                            addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(radius)))
                        }
                    }
                    clip = true
                    shadowElevation = if (immersive) 0f else shadowPx * smoothVideoStep(p)
                    translationY = coverOffset * (1f - p.coerceIn(0f, 1f))
                }
                .drawWithContent {
                    drawContent()
                    if (outline.alpha > 0f) {
                        val p = body.value
                        val w = widthAt(p, lead.value)
                        val h = heightAt(p, lead.value)
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
                        val p = body.value
                        alpha = 1f - contentHandover(p)
                        if (immersive) {
                            // Artwork adheres to the moving contour, but the
                            // underlying TextureView stays correctly proportioned.
                            val stretch = immersiveJellyStretch(p, lead.value)
                            scaleX = 1f + stretch * 0.56f
                            scaleY = 1f - stretch * 0.30f
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
 * Two springs create a perceptible, BOUNDED trailing deformation. Sampling
 * only body progress and clipping it to 0..1 in the previous version erased
 * the settling motion altogether: the two springs were nearly indistinguishable.
 *
 * Inertia is strongest in the middle, but a tiny endpoint rebound remains
 * while the body overshoots 0/1. Everything is EXACT at rest.
 */
internal fun immersiveJellyStretch(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val middle = sin((PI * p).toFloat()).coerceAtLeast(0f)
    val lag = (lead - body).coerceIn(-0.55f, 0.55f)
    val recoil = (body - p) * 0.36f
    return (lag * 0.21f * middle + recoil).coerceIn(-0.042f, 0.042f)
}

internal fun immersiveEdgeTension(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val middle = sin((PI * p).toFloat()).coerceAtLeast(0f)
    return ((lead - body) * middle).coerceIn(-0.42f, 0.42f)
}
