/* Capsule MUSIC — track-scoped shared artwork, one physical progress. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The overlay uses the already decoded destination painter. It never requests a
 * bitmap, captures a screenshot, or owns an animation clock. Only matching media
 * identities and measured, attached endpoints may participate in a transition.
 */
internal class PlayerArtworkMotion(private val state: BottomSheetState) {
    internal class Endpoint {
        var coordinates by mutableStateOf<LayoutCoordinates?>(null)
        var mediaId by mutableStateOf<String?>(null)
        var painter by mutableStateOf<Painter?>(null)
        var radius by mutableStateOf(0.dp)
        var contentScale by mutableStateOf(ContentScale.Crop)
    }

    private data class DockGeometry(val mediaId: String, val bounds: Rect, val radius: Dp, val viewport: IntSize, val collapsedBound: Dp)
    private var dockGeometry by mutableStateOf<DockGeometry?>(null)
    var root by mutableStateOf<LayoutCoordinates?>(null)
    var source by mutableStateOf<Endpoint?>(null)
    var destination by mutableStateOf<Endpoint?>(null)
    private var activeMediaId by mutableStateOf<String?>(null)
    private var activePainter by mutableStateOf<Painter?>(null)
    private var capturedSource by mutableStateOf<Rect?>(null)
    private var capturedDestination by mutableStateOf<Rect?>(null)
    private var capturedSourceRadius by mutableStateOf(0.dp)
    private var capturedDestinationRadius by mutableStateOf(0.dp)
    private var capturedScale by mutableStateOf(ContentScale.Crop)
    private val clip = Path()
    private val frameClip = Path()
    var compactMaterial by mutableStateOf<Pair<Color, Color>?>(null)

    val progress: Float get() = state.visualProgress

    fun attach(endpoint: Endpoint, mini: Boolean) {
        if (mini) source = endpoint else destination = endpoint
    }

    fun detach(endpoint: Endpoint, mini: Boolean) {
        if (mini && source === endpoint) source = null
        if (!mini && destination === endpoint) destination = null
    }

    var sharedArtworkEnabled by mutableStateOf(true)

    // The Mini's expensive playback subtree is retired at fullscreen rest.
    // Keep only its last measured rectangle, never a screenshot or old painter.
    // A cached dock is valid only for this exact track and viewport.
    fun recordDockGeometry() {
        if (progress > 0.035f || state.isDismissed || !state.isCollapsed) return
        val host = root ?: return
        val endpoint = source ?: return
        val id = endpoint.mediaId ?: return
        if (!host.isAttached || endpoint.painter == null) return
        val rect = bounds(endpoint) ?: return
        dockGeometry = DockGeometry(id, rect, endpoint.radius, host.size, state.collapsedBound)
    }

    private fun matchingDock(id: String): DockGeometry? {
        val host = root ?: return null
        if (!host.isAttached) return null
        val endpoint = source
        if (endpoint?.mediaId != null && endpoint.mediaId != id) return null
        return dockGeometry?.takeIf { it.mediaId == id && it.viewport == host.size && it.collapsedBound == state.collapsedBound }
    }

    fun matchingId(): String? {
        if (!sharedArtworkEnabled) return null
        val b = destination ?: return null
        val id = b.mediaId ?: return null
        if (b.painter == null || root?.isAttached != true ||
            b.coordinates?.isAttached != true || matchingDock(id) == null) return null
        return id
    }

    // Join a trip only near its starting anchor. A late decode must not suddenly
    // teleport artwork halfway through. A reversal retains the existing match.
    fun reconcile() {
        val p = progress
        val id = matchingId()
        fun clear() {
            activeMediaId = null
            activePainter = null
            capturedSource = null
            capturedDestination = null
        }
        if (state.isDismissed || id == null || activeMediaId != id) clear()
        if (!state.isAnimationRunning && !state.compactOpenPress && (p <= 0f || p >= 1f)) {
            clear()
            return
        }
        if (id != null && activeMediaId == null &&
            ((state.isExpandedOrExpanding && p <= 0.07f) ||
                (state.isCollapsedOrCollapsing && p >= 0.985f))) {
            val dock = matchingDock(id) ?: return
            val b = destination ?: return
            // Capture geometry once per trip. Layer transforms and recomposition
            // must not retarget the shared rectangle on every rendered frame.
            capturedSource = dock.bounds
            capturedDestination = bounds(b) ?: return
            capturedSourceRadius = dock.radius
            capturedDestinationRadius = b.radius
            capturedScale = b.contentScale
            activePainter = b.painter
            activeMediaId = id
        }
    }

    private fun bounds(endpoint: Endpoint): Rect? {
        val host = root ?: return null
        val child = endpoint.coordinates ?: return null
        if (!host.isAttached || !child.isAttached) return null
        val a = host.localPositionOf(child, Offset.Zero)
        val b = host.localPositionOf(child, Offset(child.size.width.toFloat(), child.size.height.toFloat()))
        if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return null
        val rect = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        return rect.takeIf { it.width > 1f && it.height > 1f }
    }

    private fun active(): Boolean =
        activeMediaId != null && activePainter != null && activeMediaId == matchingId() &&
            capturedSource != null && capturedDestination != null

    fun ownsArtwork(mediaId: String): Boolean = active() && activeMediaId == mediaId

    fun foregroundOpacity(): Float = if (active()) playerSharedForegroundOpacity(progress)
        else playerContentOpacity(progress)

    fun endpointOpacity(endpoint: Endpoint, mini: Boolean): Float =
        if (!active()) 1f
        else if (mini && source === endpoint) playerMiniForegroundLayerAlpha(progress)
        else if (!mini && destination === endpoint) playerSharedDestinationAlpha(progress)
        else 1f

    fun nativeArtworkOpacity(mediaId: String): Float =
        if (ownsArtwork(mediaId)) playerSharedDestinationAlpha(progress) else 1f

    fun draw(scope: DrawScope, frame: Rect, frameRadius: Float) {
        if (!active()) return
        val from = capturedSource ?: return
        val to = capturedDestination ?: return
        val painter = activePainter ?: return
        val p = progress
        val alpha = playerSharedOverlayAlpha(p)
        if (alpha <= 0f || playerPresentationOpacity(p) <= 0f) return
        val t = playerArtworkTravel(p, 0.07f)
        val rect = playerContainedArtworkBounds(from, to, t, frame)
        if (rect.width <= 0f || rect.height <= 0f) return
        with(scope) {
            val radius = (capturedSourceRadius.toPx() + (capturedDestinationRadius.toPx() - capturedSourceRadius.toPx()) * t)
                .coerceIn(0f, minOf(rect.width, rect.height) * 0.5f)
            clip.reset()
            clip.addRoundRect(RoundRect(rect, CornerRadius(radius, radius)))
            // Same decoded painter, with a centered crop during the trip and
            // the real destination's Fit/Crop policy at the final handoff.
            val intrinsic = painter.intrinsicSize
            val drawSize = if (intrinsic != Size.Unspecified && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                intrinsic.width > 0f && intrinsic.height > 0f) {
                val crop = ContentScale.Crop.computeScaleFactor(intrinsic, rect.size)
                val target = capturedScale.computeScaleFactor(intrinsic, rect.size)
                Size(intrinsic.width * (crop.scaleX + (target.scaleX - crop.scaleX) * t),
                    intrinsic.height * (crop.scaleY + (target.scaleY - crop.scaleY) * t))
            } else rect.size
            // The shared cover cannot fly ahead of its container. Both masks
            // use the same progress, so neither background nor photo overlaps Home.
            frameClip.reset()
            frameClip.addRoundRect(RoundRect(frame, CornerRadius(frameRadius, frameRadius)))
            clipPath(frameClip) {
                clipPath(clip) {
                    translate(rect.center.x - drawSize.width * 0.5f, rect.center.y - drawSize.height * 0.5f) {
                        with(painter) { draw(drawSize, alpha = alpha) }
                    }
                }
            }
        }
    }
}

internal fun playerArtworkBounds(from: Rect, to: Rect, progress: Float): Rect {
    val t = playerPhase(progress, 0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * t
    return Rect(mix(from.left, to.left), mix(from.top, to.top),
        mix(from.right, to.right), mix(from.bottom, to.bottom))
}

/** Move the entire photo inside the aperture, never cut off its top as it leads. */
internal fun playerContainedArtworkBounds(from: Rect, to: Rect, progress: Float, frame: Rect): Rect {
    val desired = playerArtworkBounds(from, to, progress)
    val width = desired.width.coerceAtMost(frame.width.coerceAtLeast(0f))
    val height = desired.height.coerceAtMost(frame.height.coerceAtLeast(0f))
    val left = desired.left.coerceIn(frame.left, (frame.right - width).coerceAtLeast(frame.left))
    val top = desired.top.coerceIn(frame.top, (frame.bottom - height).coerceAtLeast(frame.top))
    return Rect(left, top, left + width, top + height)
}

internal val LocalPlayerArtworkMotion = staticCompositionLocalOf<PlayerArtworkMotion?> { null }

@Composable
internal fun Modifier.playerArtworkEndpoint(
    mediaId: String?,
    painter: Painter?,
    mini: Boolean,
    radius: Dp,
    contentScale: ContentScale = ContentScale.Crop,
    parentOwnsOpacity: Boolean = false,
): Modifier {
    val motion = LocalPlayerArtworkMotion.current ?: return this
    val endpoint = remember(motion, mediaId, mini) { PlayerArtworkMotion.Endpoint() }
    SideEffect {
        endpoint.mediaId = mediaId
        endpoint.painter = painter
        endpoint.radius = radius
        endpoint.contentScale = contentScale
        if (mini) motion.recordDockGeometry()
    }
    DisposableEffect(motion, endpoint, mini) {
        motion.attach(endpoint, mini)
        onDispose { motion.detach(endpoint, mini) }
    }
    return onGloballyPositioned {
        endpoint.coordinates = it
        if (mini) motion.recordDockGeometry()
    }.then(if (parentOwnsOpacity) Modifier else Modifier.graphicsLayer {
        alpha = motion.endpointOpacity(endpoint, mini)
    })
}
