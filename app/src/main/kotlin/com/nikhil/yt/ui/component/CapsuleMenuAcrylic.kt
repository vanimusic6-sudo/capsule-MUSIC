/*
 * Capsule Music — original, GPU-light acrylic surface.
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
// no time-driven shimmer, no TextureView readbacks or RenderEffect layers.
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
        val alpha = if (level > 238) 17 else 5 + (level % 8)
        val ink = if (level < 100) 0 else 255
        pixels[index] = (alpha shl 24) or (ink shl 16) or (ink shl 8) or ink
    }
    val image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            ImageShader(image, TileMode.Repeated, TileMode.Repeated)
    }
}
