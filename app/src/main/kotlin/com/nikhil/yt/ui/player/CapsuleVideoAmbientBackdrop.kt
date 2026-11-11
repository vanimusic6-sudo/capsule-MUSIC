/*
 * Capsule MUSIC — continuous ambient-video colour transitions.
 *
 * An already decoded video supplies only seven colours. Changing colours
 * prepares TWO small, cached images; the actual transition is a GPU alpha
 * crossfade, not a per-frame Bitmap allocation or Compose recomposition.
 */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

internal const val VIDEO_AMBIENT_BLEND_DURATION_MS = 1_850

/**
 * Resolve the *current visible* palette before interrupting a crossfade.
 * New samples can arrive before the previous animation finishes; beginning
 * at an old target would otherwise cause a jump in the background colours.
 */
internal fun capsuleAmbientPaletteAt(
    previous: List<Color>,
    currentTarget: List<Color>,
    progress: Float,
): List<Color> {
    if (previous.size != currentTarget.size) return currentTarget
    return previous.indices.map { index ->
        lerp(previous[index], currentTarget[index], progress.coerceIn(0f, 1f))
    }
}

@Composable
internal fun CapsuleVideoAmbientBackdrop(
    bands: List<Color>,
    modifier: Modifier = Modifier,
    fallbackFloor: Color = Color(0xFF101016),
    coverFraction: Float = 0.52f,
    tailFraction: Float = 0.06f,
    texture: Float = 0f,
    landscape: Boolean = false,
    nearWhite: Boolean = false,
) {
    var previous by remember { mutableStateOf(bands) }
    var target by remember { mutableStateOf(bands) }
    val blend = remember { Animatable(1f) }

    LaunchedEffect(bands) {
        if (bands.isEmpty() || target == bands) return@LaunchedEffect
        // Snapshot the interrupted blend, without ever asking Compose to
        // interpolate seven colours on every screen frame.
        previous = capsuleAmbientPaletteAt(previous, target, blend.value)
        target = bands.toList()
        blend.snapTo(0f)
        blend.animateTo(
            1f,
            tween(durationMillis = VIDEO_AMBIENT_BLEND_DURATION_MS,
                easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier.drawWithCache {
            // Only the two palette endpoints allocate bitmaps; on subsequent
            // vsyncs the GPU simply blends the two immutable textures.
            val first = immersiveSpatialFieldBitmap(
                previous, fallbackFloor, coverFraction, tailFraction,
                texture, landscape, nearWhite, photoOverlay = false,
            )
            val second = if (previous == target) first else immersiveSpatialFieldBitmap(
                target, fallbackFloor, coverFraction, tailFraction,
                texture, landscape, nearWhite, photoOverlay = false,
            )
            val destination = IntSize(
                size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1),
            )
            onDrawBehind {
                val progress = blend.value
                if (progress < 1f && first !== second) {
                    drawImage(
                        image = first,
                        dstSize = destination,
                        filterQuality = FilterQuality.Medium,
                    )
                    drawImage(
                        image = second,
                        dstSize = destination,
                        alpha = progress.coerceIn(0f, 1f),
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawImage(
                        image = second,
                        dstSize = destination,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        },
    )
}
