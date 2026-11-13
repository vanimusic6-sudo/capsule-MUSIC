/*
 * Capsule MUSIC — palette from real artwork pixels, with no synthesized hues.
 * Each colour remains faithful to a prominent region of the actual sleeve.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

private class SceneBucket {
    var count = 0
    var red = 0f
    var green = 0f
    var blue = 0f
    var saturated = 0f
    var brightness = 0f

    fun add(red: Float, green: Float, blue: Float, sat: Float, value: Float) {
        count++
        this.red += red
        this.green += green
        this.blue += blue
        saturated += sat
        brightness += value
    }

    fun color() = Color(red / count, green / count, blue / count)
    val saturation get() = saturated / count.coerceAtLeast(1)
    val value get() = brightness / count.coerceAtLeast(1)
}

/**
 * Three *real* colours: visual background / most meaningful accent / supporting
 * accent. Hue-shifted filler colours and compulsory minimum saturation are
 * deliberately forbidden, including for all-grey and pure-black artwork.
 */
internal fun capsuleArtworkScenePalette(bitmap: Bitmap): List<Color> {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return List(3) { Color(0xFF17171C) }
    }
    // 30×30 stratified pixels are enough for a stable palette while allowing a
    // bright moon or small coloured subject to survive large dark regions.
    val buckets = Array(32) { SceneBucket() }
    val hsv = FloatArray(3)
    for (sy in 0 until 30) for (sx in 0 until 30) {
        val x = ((sx + 0.5f) * bitmap.width / 30f).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((sy + 0.5f) * bitmap.height / 30f).toInt().coerceIn(0, bitmap.height - 1)
        val argb = bitmap.getPixel(x, y)
        if ((argb ushr 24 and 255) < 128) continue
        android.graphics.Color.colorToHSV(argb, hsv)
        val rr = (argb ushr 16 and 255) / 255f
        val gg = (argb ushr 8 and 255) / 255f
        val bb = (argb and 255) / 255f
        // 24 hue families for chromatic regions + 8 brightness bins for
        // neutrals. Blacks, whites, greys and real coloured accents all count.
        val index = if (hsv[1] < 0.17f || hsv[2] < 0.15f) {
            24 + (hsv[2] * 7.999f).toInt().coerceIn(0, 7)
        } else {
            (hsv[0] / 15f).toInt().coerceIn(0, 23)
        }
        buckets[index].add(rr, gg, bb, hsv[1], hsv[2])
    }
    val present = buckets.indices.filter { buckets[it].count > 0 }
    if (present.isEmpty()) return List(3) { Color(0xFF17171C) }

    val base = present.maxByOrNull { buckets[it].count }!!
    val colourful = present.filter { it < 24 }
    // Area matters: a one-pixel logo must not take over the entire wallpaper.
    // Chromatic contrast matters too: a genuine red tree should survive a
    // larger graphite sky. Rank each hue by actual pixel coverage and colour.
    fun strength(index: Int): Float {
        val b = buckets[index]
        return b.count * (0.55f + b.saturation * 0.85f) *
            (0.65f + b.value * 0.35f)
    }
    val accent = colourful.maxByOrNull(::strength) ?: base
    fun distinct(a: Int, b: Int): Boolean {
        if (a >= 24 || b >= 24) return a != b
        val distance = abs(a - b)
        return minOf(distance, 24 - distance) >= 3
    }
    val secondary = present
        .filter { it != accent && distinct(it, accent) }
        .maxByOrNull { strength(it) * (if (it >= 24) 0.85f else 1f) }
        ?: base
    return listOf(buckets[base].color(), buckets[accent].color(), buckets[secondary].color())
}
