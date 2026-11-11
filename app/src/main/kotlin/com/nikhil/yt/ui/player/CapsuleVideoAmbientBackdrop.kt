/*
 * Capsule MUSIC — smooth GPU crossfades between actual 2D image-derived
 * colour fields. All CPU rasterization runs on Dispatchers.Default, so even
 * frequent video samples never block a play/pause frame or a user gesture.
 */
package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val VIDEO_AMBIENT_BLEND_DURATION_MS = 1_850

/** Retained for callers that also interpolate a legacy seven-colour palette. */
internal fun capsuleAmbientPaletteAt(
    previous: List<Color>,
    currentTarget: List<Color>,
    progress: Float,
): List<Color> {
    if (previous.size != currentTarget.size) return currentTarget
    val t = progress.coerceIn(0f, 1f)
    return previous.indices.map { lerp(previous[it], currentTarget[it], t) }
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
    field: CapsuleColorField? = null,
) {
    var previousBands by remember { mutableStateOf(bands) }
    var targetBands by remember { mutableStateOf(bands) }
    var previousField by remember { mutableStateOf(field) }
    var targetField by remember { mutableStateOf(field) }
    var fromTexture by remember { mutableStateOf<ImageBitmap?>(null) }
    var toTexture by remember { mutableStateOf<ImageBitmap?>(null) }
    val blend = remember { Animatable(1f) }

    LaunchedEffect(bands, field, fallbackFloor, coverFraction, tailFraction,
        texture, landscape, nearWhite) {
        if (bands == targetBands && field == targetField && toTexture != null) {
            return@LaunchedEffect
        }
        val fresh = withContext(Dispatchers.Default) {
            immersiveSpatialFieldBitmap(
                bands, fallbackFloor, coverFraction, tailFraction, texture,
                landscape, nearWhite, photoOverlay = false, field = field,
            )
        }
        if (toTexture == null) {
            previousBands = bands
            targetBands = bands
            previousField = field
            targetField = field
            toTexture = fresh
            blend.snapTo(1f)
            return@LaunchedEffect
        }
        val currentProgress = blend.value
        // Never jump back to the old target when video changes again before
        // the 1.85 s blend finished.
        val visibleBands = capsuleAmbientPaletteAt(
            previousBands, targetBands, currentProgress,
        )
        val visibleField = capsuleFieldAt(
            previousField, targetField, currentProgress,
        )
        val visibleImage = withContext(Dispatchers.Default) {
            immersiveSpatialFieldBitmap(
                visibleBands, fallbackFloor, coverFraction, tailFraction,
                texture, landscape, nearWhite,
                photoOverlay = false, field = visibleField,
            )
        }
        blend.snapTo(0f)
        previousBands = visibleBands
        previousField = visibleField
        targetBands = bands
        targetField = field
        fromTexture = visibleImage
        toTexture = fresh
        blend.animateTo(
            1f,
            tween(durationMillis = VIDEO_AMBIENT_BLEND_DURATION_MS,
                easing = FastOutSlowInEasing),
        )
        // No second texture survives past the completed transition.
        fromTexture = null
    }

    Box(
        modifier = modifier.drawWithCache {
            val dst = IntSize(size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1))
            onDrawBehind {
                val incoming = toTexture
                val outgoing = fromTexture
                val t = blend.value.coerceIn(0f, 1f)
                if (outgoing != null && incoming != null && t < 1f) {
                    drawImage(image = outgoing, dstSize = dst,
                        filterQuality = FilterQuality.Medium)
                    drawImage(image = incoming, dstSize = dst,
                        alpha = t, filterQuality = FilterQuality.Medium)
                } else if (incoming != null) {
                    drawImage(image = incoming, dstSize = dst,
                        filterQuality = FilterQuality.Medium)
                }
            }
        },
    )
}
