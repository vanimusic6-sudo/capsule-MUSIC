/* Capsule MUSIC — paired, measured elements on the player's existing reversible clock. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal enum class PlayerMorphPart { Surface, Artwork, Title }
private val SharedPlayerParts = arrayOf(PlayerMorphPart.Artwork, PlayerMorphPart.Title)

internal class PlayerMorphEndpoint(
    val part: PlayerMorphPart,
    val mediaId: String,
    val destination: Boolean,
    val layer: GraphicsLayer,
    val radius: Float,
) {
    var bounds by mutableStateOf(Rect.Zero)
    // Written/read within the same draw traversal, never a backwards snapshot write.
    var recorded = false
}

/**
 * Like sharedBounds/sharedElement, endpoints retain final layout and match by semantic identity.
 * The existing anchored spring owns EVERY visual property; no second transition clock or
 * per-frame seek coroutine is added. Display lists are retained, never converted to bitmaps.
 */
@Stable
internal class PlayerSharedMorph(val state: BottomSheetState) {
    var origin by mutableStateOf(Offset.Zero)
    private val nodes = mutableStateMapOf<Pair<PlayerMorphPart, Boolean>, PlayerMorphEndpoint>()
    private val sources = mutableStateMapOf<PlayerMorphPart, Pair<String, Rect>>()
    val active: Boolean get() = state.visualProgress > 0f && state.visualProgress < 1f

    fun register(node: PlayerMorphEndpoint) { nodes[node.part to node.destination] = node }
    fun remove(node: PlayerMorphEndpoint) {
        val key = node.part to node.destination
        if (nodes[key] === node) nodes.remove(key)
    }
    fun place(node: PlayerMorphEndpoint, bounds: Rect) {
        node.bounds = bounds
        if (!node.destination) sources[node.part] = node.mediaId to bounds
    }
    fun matched(node: PlayerMorphEndpoint): Boolean {
        if (node.part == PlayerMorphPart.Surface) return !node.destination
        val target = nodes[node.part to true] ?: return false
        val source = sources[node.part] ?: return false
        return source.first == target.mediaId && source.second.width > 0f &&
            target.bounds.width > 0f && node.mediaId == target.mediaId
    }
    fun container(size: Size, miniHeight: Float, dockHeight: Float, inset: Float): Rect {
        val initial = sources[PlayerMorphPart.Surface]?.second?.translate(-origin)
            ?: Rect(inset, size.height - dockHeight, size.width - inset,
                size.height - dockHeight + miniHeight)
        return playerMorphBounds(initial, Rect(Offset.Zero, size), state.visualProgress)
    }

    fun DrawScope.paint(frame: Rect) {
        val p = state.visualProgress
        if (p <= 0f || p >= 1f) return
        // The compact material stays a short, undistorted strip while its shared content leaves.
        // It never becomes a stretched palette/blur panel filling the destination.
        nodes[PlayerMorphPart.Surface to false]?.takeIf { it.recorded }?.let { source ->
            val b = source.bounds.translate(-origin)
            val scale = frame.width / b.width.coerceAtLeast(1f)
            source.layer.alpha = playerMiniDrawAlpha(p)
            withTransform({
                translate(frame.left, frame.top)
                scale(scale, scale, Offset.Zero)
            }) { drawLayer(source.layer) }
        }
        for (part in SharedPlayerParts) {
            val target = nodes[part to true] ?: continue
            if (!matched(target)) continue
            val sourceBounds = sources.getValue(part).second.translate(-origin)
            val targetBounds = target.bounds.translate(-origin)
            val bounds = playerMorphBounds(sourceBounds, targetBounds, p)
            val source = nodes[part to false]?.takeIf { it.mediaId == target.mediaId && it.recorded }
            val ink = if (target.recorded) target else source ?: continue
            val sourceRadius = source?.radius ?: if (part == PlayerMorphPart.Artwork)
                minOf(sourceBounds.width, sourceBounds.height) / 2f else 0f
            val radius = sourceRadius + (target.radius - sourceRadius) * p
            val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius, radius))) }
            clipPath(path) {
                // Photographs crop uniformly; text uses a uniform font scale and clips width.
                // Neither bitmap nor glyphs are stretched to the aspect of the shared bounds.
                val sx = bounds.width / ink.layer.size.width.coerceAtLeast(1)
                val sy = bounds.height / ink.layer.size.height.coerceAtLeast(1)
                val scale = if (part == PlayerMorphPart.Artwork) max(sx, sy) else sy
                fun paintLayer(node: PlayerMorphEndpoint, factor: Float, alpha: Float) {
                    node.layer.alpha = alpha
                    withTransform({
                        translate(bounds.left + if (part == PlayerMorphPart.Artwork)
                            (bounds.width - node.layer.size.width * factor) / 2f else 0f,
                            bounds.top + if (part == PlayerMorphPart.Artwork)
                            (bounds.height - node.layer.size.height * factor) / 2f else 0f)
                        scale(factor, factor, Offset.Zero)
                    }) { drawLayer(node.layer) }
                }
                if (part == PlayerMorphPart.Title && source != null && target.recorded) {
                    val t = ((p - 0.10f) / 0.55f).coerceIn(0f, 1f)
                    val blend = t * t * (3f - 2f * t)
                    paintLayer(source, bounds.height / source.layer.size.height.coerceAtLeast(1), 1f-blend)
                    paintLayer(target, bounds.height / target.layer.size.height.coerceAtLeast(1), blend)
                } else paintLayer(ink, scale, 1f)
            }
        }
    }
}

internal fun playerMorphBounds(source: Rect, target: Rect, progress: Float): Rect {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return Rect(source.left + (target.left - source.left) * p,
        source.top + (target.top - source.top) * p,
        source.right + (target.right - source.right) * p,
        source.bottom + (target.bottom - source.bottom) * p)
}

internal val LocalPlayerSharedMorph = staticCompositionLocalOf<PlayerSharedMorph?> { null }

/** Attach INSIDE any local clip, so the shared overlay can own the interpolated corner shape. */
internal fun Modifier.playerMorphElement(
    part: PlayerMorphPart,
    mediaId: String?,
    destination: Boolean,
    radius: Dp = 0.dp,
    enabled: Boolean = true,
): Modifier = composed {
    val morph = LocalPlayerSharedMorph.current
    if (morph == null || mediaId == null || !enabled) return@composed this
    val layer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val endpoint = remember(morph, part, mediaId, destination, layer, radiusPx) {
        PlayerMorphEndpoint(part, mediaId, destination, layer, radiusPx)
    }
    DisposableEffect(morph, endpoint) {
        morph.register(endpoint)
        onDispose { morph.remove(endpoint) }
    }
    this.onGloballyPositioned { coordinates ->
        val position = coordinates.localToRoot(Offset.Zero)
        // Do not use boundsInRoot: it is clipped by the currently growing container.
        morph.place(endpoint, Rect(position, coordinates.size.let { Size(it.width.toFloat(), it.height.toFloat()) }))
    }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        endpoint.recorded = true
        if (!morph.active || !morph.matched(endpoint)) {
            layer.alpha = 1f
            drawLayer(layer)
        }
    }
}
