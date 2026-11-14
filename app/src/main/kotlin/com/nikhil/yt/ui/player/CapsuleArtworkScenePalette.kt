/*
 * Capsule MUSIC — palette from real artwork pixels, with no synthesized hues.
 * Each colour remains faithful to a prominent region of the actual sleeve.
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

private class SceneBucket {
    var count = 0
    var red = 0f
    var green = 0f
    var blue = 0f
    var totalWeight = 0f
    var saturated = 0f
    var brightness = 0f

    fun add(red: Float, green: Float, blue: Float, sat: Float, value: Float) {
        count++
        // A highlighted real red petal should retain more influence than
        // a shadowy brown pixel in the SAME hue family. Still no invented hue.
        val weight = if (sat >= 0.17f) 0.52f + sat * 0.92f + value * 0.48f else 1f
        this.red += red * weight
        this.green += green * weight
        this.blue += blue * weight
        totalWeight += weight
        saturated += sat
        brightness += value
    }

    fun color() = Color(red / totalWeight, green / totalWeight, blue / totalWeight)
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
    return capsuleScenePalette(bitmap.capsuleColorField(bitmap.width.toFloat() / bitmap.height).cells)
}

/** One software decode supplies palette and video-style field to every player surface. */
internal fun capsuleArtworkScene(bitmap: Bitmap): CapsuleArtworkScene {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val aspect = bitmap.width.toFloat() / bitmap.height
    val artwork = bitmap.capsuleColorField(aspect, pixelBuffer = pixels)
    val videoSource = bitmap.capsuleColorField(aspect, video = true, pixelBuffer = pixels)
    val field = CapsuleVideoLightFilter().apply(videoSource, 1L)
    return CapsuleArtworkScene(capsuleScenePalette(artwork.cells), field)
}

private fun capsuleScenePalette(cells: List<Color>): List<Color> {
    val buckets = Array(32) { SceneBucket() }
    val hsv = FloatArray(3)
    for (colour in cells) {
        if (colour.alpha < 0.5f) continue
        android.graphics.Color.colorToHSV(colour.toArgb(), hsv)
        val rr = colour.red; val gg = colour.green; val bb = colour.blue
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

    // Adjacent hue bins describe one textured region, including red across
    // the 0/360 boundary. Rank their coverage without inventing a mixed hue.
    fun population(index: Int): Float {
        val neighbours = if (index < 24) listOf((index + 23) % 24, (index + 1) % 24)
            else listOf(index - 1, index + 1).filter { it in 24..31 }
        return buckets[index].count + neighbours.sumOf { buckets[it].count.toDouble() }.toFloat() * 0.65f
    }
    val base = present.maxByOrNull(::population)!!
    val minimumArea = maxOf(3f, cells.size * 0.025f)
    val eligible = present.filter { buckets[it].count >= minimumArea }
    val colourful = eligible.filter { it < 24 }
    // Area matters: a one-pixel logo must not take over the entire wallpaper.
    // Chromatic contrast matters too: a genuine red tree should survive a
    // larger graphite sky. Rank each hue by actual pixel coverage and colour.
    fun strength(index: Int): Float {
        val b = buckets[index]
        return population(index) * (0.55f + b.saturation * 0.85f) *
            (0.65f + b.value * 0.35f)
    }
    val accent = colourful.maxByOrNull(::strength) ?: base
    fun distinct(a: Int, b: Int): Boolean {
        if (a >= 24 || b >= 24) return a != b
        val distance = abs(a - b)
        return minOf(distance, 24 - distance) >= 3
    }
    val secondary = eligible
        .filter { it != accent && it != base && distinct(it, accent) }
        .maxByOrNull { strength(it) * (if (it >= 24) 0.85f else 1f) }
        ?: base
    return listOf(buckets[base].color(), buckets[accent].color(), buckets[secondary].color())
}
