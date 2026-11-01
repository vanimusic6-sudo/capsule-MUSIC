package com.nikhil.yt.ui.component

/**
 * A fresh YouTube Music browse response may supply an image resized to 120px.
 * Coil's requested decode size cannot restore missing source pixels. Request
 * the intended artwork size on known public Google image CDNs only.
 *
 * Never rewrite signed URLs, unknown providers or unrelated query strings.
 */
private val artworkBounds = Regex("""=w(\d+)-h(\d+)(?=-|$)""")
private val artworkSquare = Regex("""=s(\d+)(?=-|$)""")

internal fun capsuleArtworkUrl(url: String?, minimumPixels: Int = 512): String? {
    if (url.isNullOrBlank() || '?' in url) return url
    val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    if (host != "lh3.googleusercontent.com" && host != "yt3.ggpht.com" &&
        !host.endsWith(".googleusercontent.com")) return url
    val target = minimumPixels.coerceIn(160, 960)
    val bounds = artworkBounds.find(url)
    if (bounds != null) {
        val width = bounds.groupValues[1].toIntOrNull() ?: return url
        val height = bounds.groupValues[2].toIntOrNull() ?: return url
        if (width >= target && height >= target) return url
        if (width <= 0 || height <= 0) return url
        // Preserve the provider's aspect and crop flags. Enlarging each axis
        // independently turned wide artwork into a different square crop.
        val factor = target.toDouble() / minOf(width, height)
        return url.replaceRange(bounds.range,
            "=w${kotlin.math.ceil(width * factor).toInt()}-h${kotlin.math.ceil(height * factor).toInt()}")
    }
    val square = artworkSquare.find(url) ?: return url
    val old = square.groupValues[1].toIntOrNull() ?: return url
    return if (old < target) url.replaceRange(square.range, "=s$target") else url
}

/** Bound thumbnail bandwidth to the displayed pixel budget; share a few CDN sizes. */
internal fun thumbnailRequestPixels(width: Int?, height: Int?): Int =
    when (maxOf(width ?: 0, height ?: 0)) {
        in 1..160 -> 160
        in 161..256 -> 256
        in 257..512 -> 512
        else -> 960
    }
