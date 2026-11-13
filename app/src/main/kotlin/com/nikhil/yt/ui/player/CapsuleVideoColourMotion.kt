package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Spatial displacement in source-frame coordinates, not an autonomous wallpaper clock. */
internal data class CapsuleVideoColourMotion(val width: Int, val height: Int, val dx: FloatArray, val dy: FloatArray)

private class ColourMass {
    var weight = 0f; var x = 0f; var y = 0f; var r = 0f; var g = 0f; var b = 0f; var count = 0
}

private fun colourMasses(field: CapsuleColorField): Array<ColourMass> {
    val masses = Array(15) { ColourMass() }
    for (y in 0 until field.height) for (x in 0 until field.width) {
        val c = field.cell(x, y)
        val high = maxOf(c.red, c.green, c.blue)
        val low = minOf(c.red, c.green, c.blue)
        val chroma = high - low
        if (high < 0.09f) continue
        val bin = if (chroma < 0.10f) 12 + (high * 3f).toInt().coerceIn(0, 2) else {
            val hue = when (high) {
                c.red -> ((c.green - c.blue) / chroma + 6f) % 6f
                c.green -> (c.blue - c.red) / chroma + 2f
                else -> (c.red - c.green) / chroma + 4f
            }
            (hue * 2f).toInt().coerceIn(0, 11)
        }
        val w = 0.10f + chroma
        val m = masses[bin]
        m.weight += w; m.x += (x + 0.5f) / field.width * w; m.y += (y + 0.5f) / field.height * w
        m.r += c.red * w; m.g += c.green * w; m.b += c.blue * w; m.count++
    }
    return masses
}

internal fun capsuleVideoColourMotion(from: CapsuleColorField, to: CapsuleColorField): CapsuleVideoColourMotion {
    val a = colourMasses(from); val b = colourMasses(to)
    data class Source(val x: Float, val y: Float, val dx: Float, val dy: Float, val weight: Float)
    val sources = ArrayList<Source>()
    for (i in a.indices) {
        val old = a[i]; val next = b[i]
        if (old.count < 4 || next.count < 4) continue
        val distance = abs(old.r / old.weight - next.r / next.weight) +
            abs(old.g / old.weight - next.g / next.weight) + abs(old.b / old.weight - next.b / next.weight)
        if (distance > 0.65f) continue // a scene cut is not object movement
        val ox = old.x / old.weight; val oy = old.y / old.weight
        val nx = next.x / next.weight; val ny = next.y / next.weight
        sources += Source((ox + nx) / 2f, (oy + ny) / 2f,
            (nx - ox).coerceIn(-0.30f, 0.30f), (ny - oy).coerceIn(-0.30f, 0.30f),
            sqrt(minOf(old.weight, next.weight)) * if (i >= 12) 0.25f else 1f)
    }
    val dx = FloatArray(to.width * to.height); val dy = FloatArray(dx.size)
    for (y in 0 until to.height) for (x in 0 until to.width) {
        var sum = 0f; var vx = 0f; var vy = 0f
        for (s in sources) {
            val px = (x + 0.5f) / to.width - s.x; val py = (y + 0.5f) / to.height - s.y
            val w = s.weight * exp(-(px * px + py * py) / 0.16f)
            sum += w; vx += s.dx * w; vy += s.dy * w
        }
        if (sum > 0.00001f) { dx[y * to.width + x] = vx / sum; dy[y * to.width + x] = vy / sum }
    }
    return CapsuleVideoColourMotion(to.width, to.height, dx, dy)
}

internal fun capsuleFieldSample(field: CapsuleColorField, x: Float, y: Float): Color {
    val px = (x * field.width - 0.5f).coerceIn(0f, field.width - 1f)
    val py = (y * field.height - 0.5f).coerceIn(0f, field.height - 1f)
    val ix = px.toInt(); val iy = py.toInt()
    return androidx.compose.ui.graphics.lerp(
        androidx.compose.ui.graphics.lerp(field.cell(ix, iy), field.cell(ix + 1, iy), px - ix),
        androidx.compose.ui.graphics.lerp(field.cell(ix, iy + 1), field.cell(ix + 1, iy + 1), px - ix), py - iy,
    )
}

/** Same advection as the GPU; snapshots an interrupted transition without a backwards jump. */
internal fun capsuleAdvectedField(from: CapsuleColorField, to: CapsuleColorField,
    motion: CapsuleVideoColourMotion, fraction: Float): CapsuleColorField {
    val t = fraction.coerceIn(0f, 1f)
    if (t == 0f) return from
    if (t == 1f) return to
    return CapsuleColorField(to.width, to.height, List(to.width * to.height) { i ->
        val x = (i % to.width + 0.5f) / to.width; val y = (i / to.width + 0.5f) / to.height
        androidx.compose.ui.graphics.lerp(
            capsuleFieldSample(from, x - t * motion.dx[i], y - t * motion.dy[i]),
            capsuleFieldSample(to, x + (1f - t) * motion.dx[i], y + (1f - t) * motion.dy[i]), t,
        )
    })
}

/** Tiny immutable GPU inputs. No full-screen CPU rasterization on the live path. */
internal fun capsuleVideoFieldTexture(field: CapsuleColorField): Bitmap {
    val pixels = IntArray(field.width * field.height)
    for (y in 0 until field.height) for (x in 0 until field.width) {
        var r = 0f; var g = 0f; var b = 0f; var n = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val sx = x + dx; val sy = y + dy
            if (sx !in 0 until field.width || sy !in 0 until field.height) continue
            val c = field.cell(sx, sy); val w = ((3 - abs(dx)) * (3 - abs(dy))).toFloat()
            r += c.red * w; g += c.green * w; b += c.blue * w; n += w
        }
        pixels[y * field.width + x] = android.graphics.Color.rgb((r / n * 255).toInt(), (g / n * 255).toInt(), (b / n * 255).toInt())
    }
    return Bitmap.createBitmap(pixels, field.width, field.height, Bitmap.Config.ARGB_8888)
}

internal fun capsuleVideoMotionTexture(motion: CapsuleVideoColourMotion): Bitmap = Bitmap.createBitmap(
    IntArray(motion.dx.size) { i -> android.graphics.Color.rgb(
        (128f + motion.dx[i] / 0.6f * 255f).roundToInt().coerceIn(0, 255),
        (128f + motion.dy[i] / 0.6f * 255f).roundToInt().coerceIn(0, 255), 0) },
    motion.width, motion.height, Bitmap.Config.ARGB_8888,
)
