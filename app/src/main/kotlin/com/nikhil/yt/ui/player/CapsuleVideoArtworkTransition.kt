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
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val windowConfig = LocalConfiguration.current
    val windowWidth = with(density) { windowConfig.screenWidthDp.dp.roundToPx() }
    val windowHeight = with(density) { windowConfig.screenHeightDp.dp.roundToPx() }
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
                    spring(dampingRatio = 0.90f, stiffness = 106f, visibilityThreshold = 0.001f)
                else
                    spring(dampingRatio = 0.94f, stiffness = 162f, visibilityThreshold = 0.001f),
            )
        }
    }
    // Both player families now have a leading-edge spring. In Cosmo/Light it
    // is tighter and almost critically damped: enough for attached-edge drag,
    // not the bigger liquid wobble of Immersion.
    val lead = remember(transitionKey) { Animatable(target) }
    LaunchedEffect(lead, target, immersive) {
        if (abs(lead.value - target) > 0.0001f || abs(lead.velocity) > 0.0001f) {
            lead.animateTo(
                targetValue = target,
                animationSpec = if (immersive)
                    spring(dampingRatio = 0.84f, stiffness = 184f, visibilityThreshold = 0.001f)
                else
                    spring(dampingRatio = 0.90f, stiffness = 242f, visibilityThreshold = 0.001f),
            )
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
        // Native VIDEO dimensions are a property of the PLAYER WINDOW,
        // not the user-resizable COVER. The former implementation derived
        // both axes from slotWidth/slotHeight: shrinking Light's artwork to
        // 55% accidentally shrank VIDEO to 55% too.
        //
        // The canvas continues to reserve the user's chosen artwork footprint
        // (no controls jump); VIDEO is rendered above it as an overflowing
        // shared element, measured at a stable screen-relative 16:9 size.
        val videoWidth = capsuleStableVideoWidthPx(
            screenWidthPx = windowWidth,
            screenHeightPx = windowHeight,
            sideInsetPx = with(density) { 16.dp.roundToPx() },
        )
        val videoHeight = (videoWidth * 9f / 16f).roundToInt().coerceAtLeast(1)
        // A small, fixed overscan is reserved around Immersion so the jelly
        // silhouette cannot be cropped by its own graphics layer at the peak.
        val overscan = with(density) { (if (immersive) 8.dp else 5.dp).roundToPx() }
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
            val base = sharedVideoMorphDimension(coverWidth, videoWidth, p)
            val sticky = if (immersive) immersiveJellyStretch(p, leading)
                else sculptedStickyStretch(p, leading)
            return base * (1f + sticky)
        }
        fun heightAt(p: Float, leading: Float): Float {
            val base = sharedVideoMorphDimension(coverHeight, videoHeight, p)
            val sticky = if (immersive) immersiveJellyStretch(p, leading)
                else sculptedStickyStretch(p, leading)
            return base * (1f - sticky * 0.55f)
        }
        fun contentHandover(p: Float): Float =
            capsuleCoverVideoHandover(p, immersive)

        Box(
            Modifier
                .requiredSize(frameWidthDp, frameHeightDp)
                .graphicsLayer {
                    val p = body.value
                    val leading = lead.value
                    val w = widthAt(p, leading)
                    val h = heightAt(p, leading)
                    val adhesion = if (immersive) immersiveEdgeTension(p, leading)
                        else sculptedEdgeTension(p, leading)
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f +
                        if (immersive) adhesion * with(density) { 9.dp.toPx() } else 0f
                    // Corner radius is *part of* the moving silhouette, not
                    // another smoothstep that stays square for half the travel
                    // then suddenly rounds near the video frame. Light/Cosmo
                    // will keep their existing artwork radius at both ends.
                    val radius = capsuleMorphCornerRadius(
                        artworkRadius, videoRadius, p,
                    ).coerceIn(0f, min(w, h) / 2f)
                    // One continuous cubic contour across both styles and
                    // throughout the entire motion. Never switch between
                    // RoundRect and an elastic path mid-flight: the change in
                    // corner curvature itself caused an apparent sharp 'pop'.
                    val pullPx = with(density) {
                        (if (immersive) 6.dp else 4.dp).toPx()
                    } * adhesion
                    shape = GenericShape { _, _ ->
                        addPath(capsuleStickyContourPath(left, top, w, h, radius, pullPx))
                    }
                    clip = true
                    shadowElevation = if (immersive) 0f else shadowPx * smoothVideoStep(p)
                    translationY = coverOffset * (1f - p.coerceIn(0f, 1f))
                }
                .drawWithContent {
                    drawContent()
                    if (outline.alpha > 0f) {
                        val p = body.value
                        val leading = lead.value
                        val w = widthAt(p, leading)
                        val h = heightAt(p, leading)
                        val adhesion = if (immersive) immersiveEdgeTension(p, leading)
                            else sculptedEdgeTension(p, leading)
                        val left = (size.width - w) / 2f
                        val top = (size.height - h) / 2f +
                            if (immersive) adhesion * with(density) { 9.dp.toPx() } else 0f
                        val radius = capsuleMorphCornerRadius(artworkRadius, videoRadius, p)
                            .coerceIn(0f, min(w, h) / 2f)
                        val pullPx = with(density) {
                            (if (immersive) 6.dp else 4.dp).toPx()
                        } * adhesion
                        drawPath(
                            path = capsuleStickyContourPath(left, top, w, h, radius, pullPx),
                            color = outline,
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
                    Modifier
                        .requiredSize(videoWidthDp, videoHeightDp)
                        .graphicsLayer {
                            val p = body.value
                            val contourWidth = widthAt(p, lead.value)
                            val contourHeight = heightAt(p, lead.value)
                            // Fill (not Fit) the morphing mask during the short
                            // handover. TextureView stays 16:9 and measured ONCE:
                            // scaling this containing layer is purely GPU work.
                            // A uniform crop is preferable to black gutters and
                            // to distorted video frames/corner 'catch-up'.
                            val fill = maxOf(
                                contourWidth / videoWidth.toFloat(),
                                contourHeight / videoHeight.toFloat(),
                            ).coerceIn(0.5f, 3f)
                            // The video window is itself physical, not just
                            // a static 16:9 picture behind a rubber cover.
                            // A small velocity-correlated mass lag applies to
                            // its GPU layer (not to the native decoder's size).
                            // Keep uniform scale so faces are never stretched.
                            val lag = capsuleVideoContentLag(p, lead.value, immersive)
                            scaleX = fill * (1f + kotlin.math.abs(lag) * 0.016f)
                            scaleY = fill * (1f + kotlin.math.abs(lag) * 0.016f)
                            translationY = lag * with(density) { 3.dp.toPx() }
                        }
                        .background(Color.Black),
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
                        // Cosmo/Light: never draw a translucent photograph on
                        // top of the live VIDEO. The frame recording 9234.mp4
                        // shows precisely that double exposure, perceived as
                        // a flash. Instead reveal an always-opaque cover via
                        // a cheap horizontal clip synchronized to the spring.
                        // Immersion retains its already-approved soft fade.
                        alpha = if (immersive) 1f - contentHandover(p) else 1f
                        // The cover must follow the contour too. Previously
                        // it kept its original measured bounds while the
                        // container widened, exposing a narrow black/video
                        // stripe before the contents could meet the new edge.
                        val fill = maxOf(
                            widthAt(p, lead.value) / coverWidth.toFloat(),
                            heightAt(p, lead.value) / coverHeight.toFloat(),
                        ).coerceIn(0.5f, 3f)
                        val stretch = if (immersive) immersiveJellyStretch(p, lead.value)
                            else sculptedStickyStretch(p, lead.value)
                        scaleX = fill * (1f + stretch * 0.45f)
                        scaleY = fill * (1f - stretch * 0.25f)
                    }
                    .drawWithContent {
                        if (immersive) {
                            drawContent()
                        } else {
                            val fraction = capsuleOpaqueCoverFraction(body.value)
                            if (fraction > 0f) {
                                val halfWidth = size.width * fraction * 0.5f
                                clipRect(
                                    left = size.width * 0.5f - halfWidth,
                                    top = 0f,
                                    right = size.width * 0.5f + halfWidth,
                                    bottom = size.height,
                                ) {
                                    drawContent()
                                }
                            }
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

/**
 * A shared container transform: WIDTH and HEIGHT start and finish together.
 * The old independent stages created a two-step flatten-then-expand jump.
 */
internal fun sharedVideoMorphDimension(fromPx: Int, toPx: Int, progress: Float): Float =
    fromPx.toFloat() + (toPx - fromPx).toFloat() * smoothVideoStep(progress)

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
    return (lag * 0.16f * middle + recoil * 0.58f).coerceIn(-0.026f, 0.026f)
}

internal fun immersiveEdgeTension(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val middle = sin((PI * p).toFloat()).coerceAtLeast(0f)
    return ((lead - body) * middle).coerceIn(-0.30f, 0.30f)
}

/** Corner deformation follows the physical progress from the first frame. */
internal fun capsuleMorphCornerRadius(startPx: Float, endPx: Float, progress: Float): Float =
    startPx + (endPx - startPx) * progress.coerceIn(0f, 1f)

/** Controlled adhesive pull for Cosmo / Light; at rest there is no distortion. */
internal fun sculptedStickyStretch(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val middle = sin((PI * p).toFloat()).coerceAtLeast(0f)
    return (((lead - body) * 0.17f * middle) + (body - p) * 0.10f)
        .coerceIn(-0.016f, 0.016f)
}

internal fun sculptedEdgeTension(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    return ((lead - body) * sin((PI * p).toFloat()).coerceAtLeast(0f))
        .coerceIn(-0.25f, 0.25f)
}

/**
 * One contour implementation for the filled clip AND the outline. The curved
 * edges have zero tension at rest, so they reduce exactly to rounded corners
 * whose control points are tangent to their adjacent straight lines.
 */
internal fun capsuleStickyContourPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    pullPx: Float,
): Path = Path().apply {
    val right = left + width
    val bottom = top + height
    val radius = cornerRadius.coerceIn(0f, min(width, height) * 0.5f)
    val inner = (width - radius * 2f).coerceAtLeast(0f)
    val k = radius * 0.55228475f
    moveTo(left + radius, top)
    cubicTo(
        left + radius + inner * 0.30f, top - pullPx,
        left + radius + inner * 0.70f, top - pullPx,
        right - radius, top,
    )
    cubicTo(right - radius + k, top, right, top + radius - k, right, top + radius)
    lineTo(right, bottom - radius)
    cubicTo(right, bottom - radius + k, right - radius + k, bottom, right - radius, bottom)
    cubicTo(
        right - radius - inner * 0.30f, bottom - pullPx * 0.65f,
        right - radius - inner * 0.70f, bottom - pullPx * 0.65f,
        left + radius, bottom,
    )
    cubicTo(left + radius - k, bottom, left, bottom - radius + k, left, bottom - radius)
    lineTo(left, top + radius)
    cubicTo(left, top + radius - k, left + radius - k, top, left + radius, top)
    close()
}

/**
 * Dimensionless inertia of the VIDEO picture relative to the shared contour.
 * The largest possible translate is 3dp, with no non-uniform video stretching.
 * This stays zero before opening and after settling, including at 60 Hz.
 */
internal fun capsuleVideoContentLag(body: Float, lead: Float, immersive: Boolean): Float {
    val p = body.coerceIn(0f, 1f)
    val envelope = sin((PI * p).toFloat()).coerceAtLeast(0f)
    val gain = if (immersive) 3.2f else 2.2f
    return ((lead - body) * envelope * gain).coerceIn(-1f, 1f)
}

/**
 * Album photo becomes fully opaque before the window unfolds across most of
 * the screen on VIDEO -> AUDIO. The old .23..1.00 fade overlaid two unrelated
 * scenes for almost the entire morph, perceived as a broken transparent photo.
 * The shorter smooth fifth-order handover has zero derivative at both ends.
 */
internal fun capsuleCoverVideoHandover(body: Float, immersive: Boolean): Float {
    val start = if (immersive) 0.50f else 0.38f
    val length = if (immersive) 0.43f else 0.52f
    return smoothVideoStep((body - start) / length)
}

/**
 * VIDEO is anchored to device/player bounds, independent of the Light editor's
 * custom cover size. Landscape gets a conservative height cap so VIDEO does
 * not cover the playback controls. Pixel calculations happen only on layout/
 * configuration changes; no per-frame measurement of the native renderer.
 */
internal fun capsuleStableVideoWidthPx(
    screenWidthPx: Int,
    screenHeightPx: Int,
    sideInsetPx: Int,
): Int {
    val widthBound = (screenWidthPx - sideInsetPx.coerceAtLeast(0) * 2).coerceAtLeast(1)
    val landscape = screenWidthPx > screenHeightPx
    val heightBudget = screenHeightPx.coerceAtLeast(1) *
        (if (landscape) 0.70f else 0.46f)
    val aspectBound = (heightBudget * 16f / 9f).roundToInt().coerceAtLeast(1)
    return min(widthBound, aspectBound)
}

/**
 * Solid-cover compositing for Cosmo/Light. A shape reveal does not mix
 * semi-transparent album pixels with the underlying moving VIDEO picture.
 * Smooth fifth-order easing keeps the mask edge velocity zero at both ends,
 * while the spring itself remains interruption/reversal-safe.
 */
internal fun capsuleOpaqueCoverFraction(bodyProgress: Float): Float =
    smoothVideoStep((0.94f - bodyProgress) / 0.88f)
