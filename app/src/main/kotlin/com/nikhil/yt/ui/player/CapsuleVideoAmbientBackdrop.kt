package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val VIDEO_AMBIENT_BLEND_DURATION_MS = 120

internal fun capsuleAmbientPaletteAt(previous: List<Color>, currentTarget: List<Color>, progress: Float): List<Color> {
    if (previous.size != currentTarget.size) return currentTarget
    return previous.indices.map { lerp(previous[it], currentTarget[it], progress.coerceIn(0f, 1f)) }
}

internal const val CAPSULE_VIDEO_MOTION_SHADER = """
uniform shader previous;
uniform shader current;
uniform shader velocity;
uniform float2 resolution;
uniform float2 grid;
uniform float progress;
layout(color) uniform half4 floorColor;
half4 main(float2 point) {
    float2 uv = clamp(point / resolution, 0.0, 1.0);
    float2 source = float2(uv.x, 0.16 + 0.64 * uv.y);
    float2 displacement = (velocity.eval(source * grid).rg - float2(128.0 / 255.0)) * 0.6;
    float2 oldPoint = clamp(source - progress * displacement, 0.0, 1.0);
    float2 newPoint = clamp(source + (1.0 - progress) * displacement, 0.0, 1.0);
    half3 light = mix(previous.eval(oldPoint * grid).rgb, current.eval(newPoint * grid).rgb, progress);
    float settle = uv.y * uv.y * (3.0 - 2.0 * uv.y);
    float amount = 0.48 * (1.0 - settle) + 0.12;
    half3 base = half3(0.042, 0.045, 0.050) + floorColor.rgb * 0.12;
    return half4(mix(base, light, amount), 1.0);
}
"""

@RequiresApi(33)
internal class CapsuleVideoMotionShader {
    val shader = RuntimeShader(CAPSULE_VIDEO_MOTION_SHADER)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = this@CapsuleVideoMotionShader.shader; isDither = true }
    fun bind(previous: Bitmap, current: Bitmap, flow: Bitmap, floor: Color) {
        fun input(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        shader.setInputShader("previous", input(previous))
        shader.setInputShader("current", input(current))
        shader.setInputBuffer("velocity", input(flow))
        shader.setFloatUniform("grid", current.width.toFloat(), current.height.toFloat())
        shader.setColorUniform("floorColor", floor.toArgb())
    }
    fun dimensions(width: Float, height: Float, progress: Float) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
    }
}

private data class VideoAmbientPaint(
    val previous: Bitmap, val current: Bitmap, val motion: Bitmap,
    val fallbackFrom: ImageBitmap?, val fallbackTo: ImageBitmap?,
)

@Composable
internal fun CapsuleVideoAmbientBackdrop(
    bands: List<Color>, modifier: Modifier = Modifier,
    fallbackFloor: Color = Color(0xFF101016), coverFraction: Float = 0.52f,
    tailFraction: Float = 0.06f, texture: Float = 0f, landscape: Boolean = false,
    nearWhite: Boolean = false, field: CapsuleColorField? = null,
    onFirstTextureReady: (() -> Unit)? = null, animated: Boolean = true,
) {
    val currentReadyCallback by rememberUpdatedState(onFirstTextureReady)
    val target = remember(field, bands, fallbackFloor) {
        field ?: CapsuleColorField(CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS,
            List(CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS) { i ->
                immersiveSpatialBandColor(bands.ifEmpty { listOf(fallbackFloor) },
                    (i % CAPSULE_FIELD_COLUMNS + 0.5f) / CAPSULE_FIELD_COLUMNS)
            })
    }
    var previousField by remember { mutableStateOf(target) }
    var targetField by remember { mutableStateOf(target) }
    var motion by remember { mutableStateOf(capsuleVideoColourMotion(target, target)) }
    var prepared by remember { mutableStateOf<VideoAmbientPaint?>(null) }
    val blend = remember { Animatable(1f) }
    val modern = Build.VERSION.SDK_INT >= 33
    LaunchedEffect(target, fallbackFloor, animated) {
        val visible = capsuleAdvectedField(previousField, targetField, motion, blend.value)
        val nextMotion = capsuleVideoColourMotion(visible, target)
        val ready = withContext(Dispatchers.Default) {
            VideoAmbientPaint(capsuleVideoFieldTexture(visible), capsuleVideoFieldTexture(target),
                capsuleVideoMotionTexture(nextMotion),
                if (modern) null else capsuleVideoSpatialBitmap(visible, fallbackFloor),
                if (modern) null else capsuleVideoSpatialBitmap(target, fallbackFloor))
        }
        val first = prepared == null
        previousField = visible
        targetField = target
        motion = nextMotion
        prepared = ready
        blend.snapTo(if (first || !animated) 1f else 0f)
        currentReadyCallback?.invoke()
        if (!first && animated) blend.animateTo(1f, tween(VIDEO_AMBIENT_BLEND_DURATION_MS, easing = LinearEasing))
    }
    val engine = if (Build.VERSION.SDK_INT >= 33) remember { CapsuleVideoMotionShader() } else null
    SideEffect {
        if (Build.VERSION.SDK_INT >= 33) prepared?.let {
            engine?.bind(it.previous, it.current, it.motion, fallbackFloor)
        }
    }
    // The light field is low-frequency. A small GPU layer carries smooth
    // display-clock advection without shading every physical screen pixel.
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        val renderWidth = minOf(widthPx, 360)
        val renderHeight = (heightPx.toFloat() * renderWidth / widthPx).roundToInt().coerceAtLeast(1)
        val density = LocalDensity.current
        Box(Modifier.requiredSize(with(density) { renderWidth.toDp() }, with(density) { renderHeight.toDp() })
            .graphicsLayer {
                scaleX = widthPx.toFloat() / renderWidth; scaleY = heightPx.toFloat() / renderHeight
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithCache {
                val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                onDrawBehind {
                    val ready = prepared
                    if (Build.VERSION.SDK_INT >= 33 && engine != null && ready != null) {
                        engine.dimensions(size.width, size.height, blend.value)
                        drawIntoCanvas { it.nativeCanvas.drawPaint(engine.paint) }
                    } else if (ready != null) {
                        if (blend.value < 1f) ready.fallbackFrom?.let {
                            drawImage(it, dstSize = dst, filterQuality = FilterQuality.Medium)
                        }
                        ready.fallbackTo?.let { drawImage(it, dstSize = dst, alpha = blend.value, filterQuality = FilterQuality.Medium) }
                    }
                }
            })
    }
}
