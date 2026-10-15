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
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI
import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager

internal enum class CapsuleVideoArtworkMotion { SCULPTED, IMMERSIVE }

/** Each skin gets its own optical handoff; physical layout stays shared. */
internal enum class CapsuleVideoBlendStyle { LIGHT, COSMO, IMMERSION }

internal data class CapsuleVideoBlendSpec(
    /** Shared spring progress before the first visible VIDEO pixel. */
    val fadeStart: Float,
    /** Reversible handover distance in the same shared spring timeline. */
    val fadeSpan: Float,
)

internal fun capsuleVideoBlendSpec(style: CapsuleVideoBlendStyle): CapsuleVideoBlendSpec =
    when (style) {
        CapsuleVideoBlendStyle.LIGHT -> CapsuleVideoBlendSpec(fadeStart = 0.46f, fadeSpan = 0.40f)
        CapsuleVideoBlendStyle.COSMO -> CapsuleVideoBlendSpec(fadeStart = 0.46f, fadeSpan = 0.44f)
        CapsuleVideoBlendStyle.IMMERSION -> CapsuleVideoBlendSpec(fadeStart = 0.32f, fadeSpan = 0.68f)
    }

/**
 * Blend both children from one physical timeline, with no separately retargeted
 * alpha animations. Closing retraces opening exactly from the spring's current
 * velocity, and a completed VIDEO can stay mounted without bleeding through.
 */
internal fun capsulePhotoOpacity(progress: Float, style: CapsuleVideoBlendStyle): Float {
    val blend = capsuleVideoBlendSpec(style)
    return 1f - smoothVideoStep((progress - blend.fadeStart) / blend.fadeSpan)
}

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
    independentVideoViewport: Boolean = false,
    motion: CapsuleVideoArtworkMotion = CapsuleVideoArtworkMotion.SCULPTED,
    blendStyle: CapsuleVideoBlendStyle = CapsuleVideoBlendStyle.COSMO,
    artworkBackground: Color = Color.Transparent,
    outline: Color = Color.Transparent,
    artworkClickable: Boolean = false,
    onArtworkClick: () -> Unit = {},
    video: @Composable (active: Boolean) -> Unit,
    artwork: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val windowWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val windowHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val context = LocalContext.current
    // Never start a second animator or shader-heavy elastic edge in battery
    // saver or on devices Android identifies as low-RAM.
    val energySaver = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true ||
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isPowerSaveMode == true
    }
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
    val lead = if (energySaver) body else remember(transitionKey) { Animatable(target) }
    if (!energySaver) {
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
    }
    // Optical reveal is derived directly from body.value inside GPU layers.
    // No separate coroutine/frame-clock: this also makes the transitions
    // reproducible by Robolectric and avoids photo/video state drift.
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
        // The old coerceAtLeast(slotWidth) silently disabled the device-width
        // cap in Immersion, whose slot is full bleed. Its VIDEO window became
        // almost screen-wide while Light/Cosmo were inset. All designs now
        // share an explicit screen-relative target. Keep the user-sized Light
        // artwork slot as an upper bound when it is unusually small.
        val sharedWindowWidth = (windowWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(1)
        val availableVideoWidth = ((slotWidth * videoWidthScale).roundToInt() -
            with(density) { (videoSidePadding.toPx() * 2).roundToInt() }).coerceAtLeast(1)
        // A custom resized AUDIO cover must never shrink the VIDEO decoder:
        // only a screen-level height budget applies to Light's VIDEO target.
        val videoHeightLimit = if (independentVideoViewport)
            (windowHeight * 0.40f).roundToInt().coerceAtLeast(1)
        else slotHeight
        val videoWidth = minOf(
            if (independentVideoViewport) sharedWindowWidth else availableVideoWidth,
            sharedWindowWidth,
            (videoHeightLimit * 16f / 9f).roundToInt().coerceAtLeast(1),
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
            // Immersion is now a stretch-and-dissolve of the VIDEO picture,
            // not a rubber-edged geometric deformation of the viewport.
            val sticky = if (energySaver || immersive) 0f else sculptedStickyStretch(p, leading)
            return base * (1f + sticky)
        }
        fun heightAt(p: Float, leading: Float): Float {
            val base = sharedVideoMorphDimension(coverHeight, videoHeight, p)
            // Immersion is now a stretch-and-dissolve of the VIDEO picture,
            // not a rubber-edged geometric deformation of the viewport.
            val sticky = if (energySaver || immersive) 0f else sculptedStickyStretch(p, leading)
            return base * (1f - sticky * 0.55f)
        }
        Box(
            Modifier
                .requiredSize(frameWidthDp, frameHeightDp)
                .graphicsLayer {
                    val p = body.value
                    val leading = lead.value
                    val w = widthAt(p, leading)
                    val h = heightAt(p, leading)
                    val adhesion = if (energySaver || immersive) 0f
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
                    shadowElevation = if (immersive || energySaver) 0f else shadowPx * smoothVideoStep(p)
                    translationY = coverOffset * (1f - p.coerceIn(0f, 1f))
                }
                .drawWithContent {
                    drawContent()
                    if (outline.alpha > 0f) {
                        val p = body.value
                        val leading = lead.value
                        val w = widthAt(p, leading)
                        val h = heightAt(p, leading)
                        val adhesion = if (energySaver || immersive) 0f
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
                            val lag = if (energySaver) 0f
                                else capsuleVideoContentLag(p, lead.value, immersive)
                            // Immersion: VIDEO dissolves while gently growing,
                            // never bending its rectangular window or distorting
                            // the decoded 16:9 frame. Both axes scale together.
                            val dissolveStretch = if (immersive && !energySaver)
                                capsuleImmersionVideoDissolveStretch(capsulePhotoOpacity(p, blendStyle)) else 0f
                            val total = fill * (1f + kotlin.math.abs(lag) * 0.012f + dissolveStretch)
                            scaleX = total
                            scaleY = total
                            // The native VIDEO can stay mounted for recovery
                            // while becoming optically invisible under the
                            // outgoing photo. An actual zero-alpha renderer
                            // prevents stale VIDEO leaking through any corner
                            // even on devices with different RenderNode order.
                            alpha = capsuleVideoRenderAlpha(capsulePhotoOpacity(p, blendStyle), blendStyle)
                            translationY = lag * with(density) { (if (immersive) 1.dp else 2.dp).toPx() }
                        }
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    video(true)
                }
            }
            // This FULL-SIZE opaque matte is important: the album artwork is
            // often FIT/CROP with different dimensions and its independently
            // deformed child can leave tiny triangular gaps near the rounded
            // contour. Those gaps must show album tone, NEVER a video frame.
            // Animating the matte+bitmap together also eliminates double-alpha
            // compositing at the edges, with a single GPU opacity layer.
            Box(
                Modifier
                    // Android's VIDEO TextureView and a full-frame Compose
                    // alpha layer may be reordered across render nodes. Make
                    // the album/matte the explicit front-most child even when
                    // VIDEO is retained while closing (needsSurface=true).
                    .zIndex(1f)
                    .requiredSize(frameWidthDp, frameHeightDp)
                    .graphicsLayer { alpha = capsulePhotoOpacity(body.value, blendStyle) }
                    .background(artworkBackground.compositeOver(Color(0xFF141414))),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .requiredSize(coverWidthDp, coverHeightDp)
                        .graphicsLayer {
                            val p = body.value
                            val fill = maxOf(
                                widthAt(p, lead.value) / coverWidth.toFloat(),
                                heightAt(p, lead.value) / coverHeight.toFloat(),
                            ).coerceIn(0.5f, 3f)
                            val stretch = if (energySaver || immersive) 0f
                                else sculptedStickyStretch(p, lead.value)
                            scaleX = fill * (1f + stretch * 0.20f)
                            scaleY = fill * (1f - stretch * 0.12f)
                        },
                    contentAlignment = Alignment.Center,
                ) { artwork() }
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

/** Dissolve stretch peaks mid-blend and returns to exact 16:9 at both ends. */
internal fun capsuleImmersionVideoDissolveStretch(photoAlpha: Float): Float {
    val p = photoAlpha.coerceIn(0f, 1f)
    return (0.047f * sin((PI * p).toFloat())).coerceIn(0f, 0.047f)
}

/**
 * Fade the VIDEO renderer itself at the END of the photo handover. The opaque
 * album matte already covers the whole outline; this second, short optical
 * release ensures that a retained native VIDEO view cannot shine through
 * its corners on devices that reorder video/compositing render nodes.
 */
internal fun capsuleVideoRenderAlpha(coverAlpha: Float, style: CapsuleVideoBlendStyle): Float {
    val (start, width) = when (style) {
        CapsuleVideoBlendStyle.LIGHT -> 0.58f to 0.32f
        CapsuleVideoBlendStyle.COSMO -> 0.62f to 0.32f
        CapsuleVideoBlendStyle.IMMERSION -> 0.70f to 0.28f
    }
    return 1f - smoothVideoStep((coverAlpha - start) / width)
}
