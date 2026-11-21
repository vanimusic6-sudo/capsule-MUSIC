/*
 * Capsule Music — original, GPU-light matte menu texture.
 * GPL-3.0. No third-party blur, image readback or per-frame CPU noise.
 */
package com.nikhil.yt.ui.component

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

// Tiny deterministic tile shared by all menus. ~16KB once per process,
// Fixed sand-like microspeckle. No timer, readback, shimmer or blur.
internal val capsuleMenuGrain: Brush by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val width = 61
    val height = 67
    val pixels = IntArray(width * height)
    var seed = 0x3a5c91e
    for (index in pixels.indices) {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        val level = seed ushr 24
        val alpha = if (level > 247) 15 else 8 + (level % 5)
        val ink = if (level < 112) 24 else if (level > 208) 215 else 118
        pixels[index] = (alpha shl 24) or (ink shl 16) or (ink shl 8) or ink
    }
    val image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            ImageShader(image, TileMode.Repeated, TileMode.Repeated)
    }
}
