/* Capsule MUSIC — render one artwork image along the real Mini → Immersion trajectory.
 * The Mini's existing Coil painter is reused: no extra image request or bitmap copy. GPL-3.0. */
package com.nikhil.yt.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter

/**
 * Shared content identity, NOT another animation controller.
 * All coordinates are measured once by Compose (boundsInRoot), not inferred from hardcoded
 * thumbnail offsets. An unready image simply falls back to the normal player presentation.
 */
internal class PlayerArtworkHandoff {
    var mediaId by mutableStateOf<String?>(null)
        private set
    var sourceBoundsInRoot by mutableStateOf<Rect?>(null)
    var rootBoundsInRoot by mutableStateOf<Rect?>(null)
    var destinationBoundsInWindow by mutableStateOf<Rect?>(null)
    var sourcePainter by mutableStateOf<Painter?>(null)
        private set

    fun expect(id: String?) {
        if (mediaId == id) return
        mediaId = id
        sourcePainter = null // A new song MUST NOT animate the previous song's bitmap.
    }

    fun publish(id: String?, painter: Painter) {
        if (id != null && id == mediaId && sourcePainter !== painter) {
            sourcePainter = painter
        }
    }

    fun destination(width: Float, height: Float) {
        if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return
        val next = Rect(0f, 0f, width, height)
        if (destinationBoundsInWindow != next) destinationBoundsInWindow = next
    }
}

internal val LocalPlayerArtworkHandoff = compositionLocalOf<PlayerArtworkHandoff?> { null }

internal fun playerSharedArtworkBounds(source: Rect, target: Rect, progress: Float): Rect {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return Rect(
        left = source.left + (target.left - source.left) * p,
        top = source.top + (target.top - source.top) * p,
        right = source.right + (target.right - source.right) * p,
        bottom = source.bottom + (target.bottom - source.bottom) * p,
    )
}

/** Only one physical spring exists; this is a draw-time visibility envelope, not a tween. */
internal fun playerSharedArtworkAlpha(progress: Float): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return when {
        p <= 0.04f -> 0f
        p < 0.12f -> (p - 0.04f) / 0.08f
        p <= 0.66f -> 1f
        p < 0.90f -> (0.90f - p) / 0.24f
        else -> 0f
    }.coerceIn(0f, 1f)
}

@Composable
internal fun PlayerSharedArtwork(
    handoff: PlayerArtworkHandoff,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    // A SINGLE draw node is composed only during the finite opening/closing spring.
    // Its bitmap comes from Mini's existing Coil result; it does not allocate images per frame.
    Canvas(modifier.fillMaxSize()) {
        val painter = handoff.sourcePainter ?: return@Canvas
        val root = handoff.rootBoundsInRoot ?: return@Canvas
        val sourceInRoot = handoff.sourceBoundsInRoot ?: return@Canvas
        val target = handoff.destinationBoundsInWindow ?: return@Canvas
        val p = progress().coerceIn(0f, 1f)
        val alpha = playerSharedArtworkAlpha(p)
        if (alpha <= 0f) return@Canvas

        val source = Rect(
            sourceInRoot.left - root.left,
            sourceInRoot.top - root.top,
            sourceInRoot.right - root.left,
            sourceInRoot.bottom - root.top,
        )
        val bounds = playerSharedArtworkBounds(source, target, p)
        if (bounds.width < 1f || bounds.height < 1f) return@Canvas

        // Mini's real circular artwork is the SOURCE shape. Immersion's full-width image is the
        // DESTINATION shape. Continuous crop keeps the image aspect ratio correct at every size.
        val corner = (minOf(source.width, source.height) / 2f) * (1f - p)
        val clip = Path().apply {
            addRoundRect(RoundRect(bounds, CornerRadius(corner, corner)))
        }
        val natural = painter.intrinsicSize
        val imageWidth = natural.width.takeIf { it.isFinite() && it > 0f } ?: bounds.width
        val imageHeight = natural.height.takeIf { it.isFinite() && it > 0f } ?: bounds.height
        val fillScale = maxOf(bounds.width / imageWidth, bounds.height / imageHeight)
        val drawWidth = imageWidth * fillScale
        val drawHeight = imageHeight * fillScale
        clipPath(clip) {
            withTransform({
                translate(
                    left = bounds.left + (bounds.width - drawWidth) * 0.5f,
                    top = bounds.top + (bounds.height - drawHeight) * 0.5f,
                )
            }) {
                with(painter) {
                    draw(size = Size(drawWidth, drawHeight), alpha = alpha)
                }
            }
        }
    }
}
