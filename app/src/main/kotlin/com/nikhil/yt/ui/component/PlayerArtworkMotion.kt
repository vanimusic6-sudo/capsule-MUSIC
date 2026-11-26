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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
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

    var root by mutableStateOf<LayoutCoordinates?>(null)
    var source by mutableStateOf<Endpoint?>(null)
    var destination by mutableStateOf<Endpoint?>(null)
    private var activeMediaId by mutableStateOf<String?>(null)
    private var activePainter by mutableStateOf<Painter?>(null)
    private var startProgress by mutableStateOf(0.005f)
    private val clip = Path()

    val progress: Float get() = state.visualProgress

    fun attach(endpoint: Endpoint, mini: Boolean) {
        if (mini) source = endpoint else destination = endpoint
    }

    fun detach(endpoint: Endpoint, mini: Boolean) {
        if (mini && source === endpoint) source = null
        if (!mini && destination === endpoint) destination = null
    }

    fun matchingId(): String? {
        val a = source ?: return null
        val b = destination ?: return null
        val id = a.mediaId ?: return null
        if (id != b.mediaId || a.painter == null || b.painter == null ||
            root?.isAttached != true || a.coordinates?.isAttached != true ||
            b.coordinates?.isAttached != true) return null
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
        }
        if (state.isDismissed || id == null || activeMediaId != id) clear()
        if (!state.isAnimationRunning && !state.compactOpenPress && (p <= 0f || p >= 1f)) {
            clear()
            return
        }
        if (id != null && activeMediaId == null &&
            ((state.isExpandedOrExpanding && p <= 0.16f) ||
                (state.isCollapsedOrCollapsing && p >= 0.86f))) {
            // When preparation finishes on the first moving frame, start the
            // shared bounds at the current dock rather than jumping ahead.
            startProgress = if (state.isExpandedOrExpanding) p.coerceIn(0.005f, 0.16f) else 0.005f
            activePainter = destination?.painter
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
            progress > startProgress && progress < 0.985f &&
            source?.let(::bounds) != null && destination?.let(::bounds) != null

    fun suppress(endpoint: Endpoint, mini: Boolean): Boolean =
        active() && (if (mini) source === endpoint else destination === endpoint && progress < 0.82f)

    fun draw(scope: DrawScope) {
        if (!active()) return
        val a = source ?: return
        val b = destination ?: return
        val from = bounds(a) ?: return
        val to = bounds(b) ?: return
        val painter = activePainter ?: return
        val p = progress
        val t = playerPhase(p, startProgress, 0.82f)
        val rect = playerArtworkBounds(from, to, t)
        val alpha = 1f - playerPhase(p, 0.82f, 0.985f)
        with(scope) {
            val radius = (a.radius.toPx() + (b.radius.toPx() - a.radius.toPx()) * t)
                .coerceIn(0f, minOf(rect.width, rect.height) * 0.5f)
            clip.reset()
            clip.addRoundRect(RoundRect(rect, CornerRadius(radius, radius)))
            // Same decoded painter, with a centered crop during the trip and
            // the real destination's Fit/Crop policy at the final handoff.
            val intrinsic = painter.intrinsicSize
            val drawSize = if (intrinsic != Size.Unspecified && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                intrinsic.width > 0f && intrinsic.height > 0f) {
                val crop = ContentScale.Crop.computeScaleFactor(intrinsic, rect.size)
                val target = b.contentScale.computeScaleFactor(intrinsic, rect.size)
                Size(intrinsic.width * (crop.scaleX + (target.scaleX - crop.scaleX) * t),
                    intrinsic.height * (crop.scaleY + (target.scaleY - crop.scaleY) * t))
            } else rect.size
            clipPath(clip) {
                translate(rect.center.x - drawSize.width * 0.5f, rect.center.y - drawSize.height * 0.5f) {
                    with(painter) { draw(drawSize, alpha = alpha) }
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

internal val LocalPlayerArtworkMotion = staticCompositionLocalOf<PlayerArtworkMotion?> { null }

@Composable
internal fun Modifier.playerArtworkEndpoint(
    mediaId: String?,
    painter: Painter?,
    mini: Boolean,
    radius: Dp,
    contentScale: ContentScale = ContentScale.Crop,
): Modifier {
    val motion = LocalPlayerArtworkMotion.current ?: return this
    val endpoint = remember(motion, mediaId, mini) { PlayerArtworkMotion.Endpoint() }
    SideEffect {
        endpoint.mediaId = mediaId
        endpoint.painter = painter
        endpoint.radius = radius
        endpoint.contentScale = contentScale
    }
    DisposableEffect(motion, endpoint, mini) {
        motion.attach(endpoint, mini)
        onDispose { motion.detach(endpoint, mini) }
    }
    return onGloballyPositioned { endpoint.coordinates = it }
        .drawWithContent { if (!motion.suppress(endpoint, mini)) drawContent() }
}
