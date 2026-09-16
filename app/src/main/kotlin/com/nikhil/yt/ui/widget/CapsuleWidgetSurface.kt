/**
 * Capsule MUSIC
 * How a widget's panel colour is derived from the artwork.
 * GPL-3.0
 */

package com.nikhil.yt.ui.widget

import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.max

/**
 * The deepest the panel is ever allowed to be, as a fraction of full brightness.
 *
 * A home-screen widget sits on somebody's wallpaper, at whatever brightness they keep their phone,
 * for as long as the phone is on. Every one of those is a reason for it to be dark.
 */
private const val PANEL_CEILING = 0.20f

/** How far the artwork colour is pulled towards grey before it is darkened. */
private const val DESATURATION = 0.45f

/**
 * A panel colour for [artworkColor].
 *
 * The old rule was `palette.dominantSwatch.rgb`, used raw. The dominant colour of a cover is
 * usually its loudest one, so widgets came out as large saturated blocks — a lime panel for one
 * album and a hot pink one for the next, each of them the brightest thing on the home screen and
 * each of them burning an OLED panel all day to be there.
 *
 * This keeps the artwork's hue and throws away its volume: pull most of the way towards grey, then
 * compress the result into a deep band. What comes out is always a dark tint — recognisably from
 * the cover, never competing with it or with the wallpaper — and, because it is always dark, the
 * text on it is always light, which removes the contrast guesswork entirely.
 */
fun capsuleWidgetSurface(artworkColor: Int): Int {
    val red = Color.red(artworkColor) / 255f
    val green = Color.green(artworkColor) / 255f
    val blue = Color.blue(artworkColor) / 255f

    val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
    val mutedRed = red + (luminance - red) * DESATURATION
    val mutedGreen = green + (luminance - green) * DESATURATION
    val mutedBlue = blue + (luminance - blue) * DESATURATION

    val brightest = max(mutedRed, max(mutedGreen, mutedBlue))
    // A cover that is already darker than the ceiling is left alone rather than lifted to it: the
    // ceiling is a limit, not a target.
    val scale = if (brightest > PANEL_CEILING) PANEL_CEILING / brightest else 1f

    return Color.rgb(
        ((mutedRed * scale) * 255f).toInt().coerceIn(0, 255),
        ((mutedGreen * scale) * 255f).toInt().coerceIn(0, 255),
        ((mutedBlue * scale) * 255f).toInt().coerceIn(0, 255),
    )
}

/**
 * Picks the swatch to derive the panel from.
 *
 * Preferring the muted swatches means starting from a colour that is already calm, so what
 * [capsuleWidgetSurface] has to remove is smaller and the hue survives it better.
 */
fun capsuleWidgetArtworkColor(palette: Palette): Int =
    (
        palette.darkMutedSwatch
            ?: palette.mutedSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
    )?.rgb ?: CAPSULE_WIDGET_FALLBACK_SURFACE

/** Used before any artwork has been seen, and when a cover yields no usable swatch. */
const val CAPSULE_WIDGET_FALLBACK_SURFACE: Int = 0xFF16181C.toInt()

/** Always light, because the panel is always dark by construction. */
const val CAPSULE_WIDGET_INK: Int = 0xFFF2F3F5.toInt()

/** The unfilled half of the progress line, and the outline on the playlist tiles. */
const val CAPSULE_WIDGET_INK_DIM: Int = 0x59F2F3F5
