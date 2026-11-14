package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.exp

/**
 * Frame-driven light, not optical-flow guesses. Spatial kernels are built
 * once per capture session; scratch buffers are reused for all video frames.
 * Both axes keep the actual positions of broad colour regions, while small
 * stripes/subtitles and rapid codec flicker cannot become wallpaper shapes.
 */
internal class CapsuleVideoLightFilter(
    private val width: Int = CAPSULE_FIELD_COLUMNS,
    private val height: Int = CAPSULE_FIELD_ROWS,
) {
    private fun weights(size: Int, sigma: Float) = FloatArray(size * size).also { result ->
        for (out in 0 until size) {
            var total = 0f
            for (source in 0 until size) {
                val distance = (source - out) / sigma
                val weight = exp(-0.5f * distance * distance)
                result[out * size + source] = weight
                total += weight
            }
            for (source in 0 until size) result[out * size + source] /= total
        }
    }
    private val horizontalWeights = weights(width, width * 0.18f)
    private val verticalWeights = weights(height, height * 0.20f)
    private val horizontal = FloatArray(width * height * 3)
    private val settled = FloatArray(horizontal.size)
    private var previousNs = 0L
    private var initialised = false

    fun apply(field: CapsuleColorField, nowNs: Long): CapsuleColorField {
        require(field.width == width && field.height == height)
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 3
            var r = 0f; var g = 0f; var b = 0f
            for (sx in 0 until width) {
                val colour = field.cells[y * width + sx]
                val weight = horizontalWeights[x * width + sx]
                r += colour.red * weight; g += colour.green * weight; b += colour.blue * weight
            }
            horizontal[index] = r; horizontal[index + 1] = g; horizontal[index + 2] = b
        }
        // A sustained new scene follows promptly, but a one-frame flash cannot
        // throw the whole page to its peak brightness. No independent timer.
        val elapsed = (nowNs - previousNs).coerceIn(1_000_000L, 500_000_000L)
        val follow = if (!initialised) 1f else 1f - exp(-elapsed / 220_000_000f)
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 3
            var r = 0f; var g = 0f; var b = 0f
            for (sy in 0 until height) {
                val sample = (sy * width + x) * 3
                val weight = verticalWeights[y * height + sy]
                r += horizontal[sample] * weight
                g += horizontal[sample + 1] * weight
                b += horizontal[sample + 2] * weight
            }
            settled[index] += (r - settled[index]) * follow
            settled[index + 1] += (g - settled[index + 1]) * follow
            settled[index + 2] += (b - settled[index + 2]) * follow
        }
        previousNs = nowNs
        initialised = true
        // Immutable output: the GPU/Compose never sees the reused scratch arrays.
        return CapsuleColorField(width, height, List(width * height) { i ->
            Color(settled[i * 3].coerceIn(0f, 1f), settled[i * 3 + 1].coerceIn(0f, 1f),
                settled[i * 3 + 2].coerceIn(0f, 1f))
        })
    }
}

/** Filtering already happened once on the capture worker. Upload only 320 pixels. */
internal fun capsuleVideoFieldTexture(field: CapsuleColorField): Bitmap = Bitmap.createBitmap(
    IntArray(field.cells.size) { field.cells[it].toArgb() },
    field.width, field.height, Bitmap.Config.ARGB_8888,
)
