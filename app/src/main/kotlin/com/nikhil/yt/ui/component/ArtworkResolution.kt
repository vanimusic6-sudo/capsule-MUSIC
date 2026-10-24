package com.nikhil.yt.ui.component

/**
 * A fresh YouTube Music browse response may supply an image resized to 120px.
 * Coil's requested decode size cannot restore missing source pixels. Request
 * the intended artwork size on known public Google image CDNs only.
 *
 * Never rewrite signed URLs, unknown providers or unrelated query strings.
 */
internal fun capsuleArtworkUrl(url: String?, minimumPixels: Int = 512): String? {
    if (url.isNullOrBlank() || '?' in url) return url
    val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    if (host != "lh3.googleusercontent.com" && host != "yt3.ggpht.com" &&
        !host.endsWith(".googleusercontent.com")) return url
    val target = minimumPixels.coerceIn(160, 960)
    val bounds = Regex("""=w(\d+)-h(\d+)(?=-|$)""").find(url)
    if (bounds != null) {
        val width = bounds.groupValues[1].toIntOrNull() ?: return url
        val height = bounds.groupValues[2].toIntOrNull() ?: return url
        if (width >= target && height >= target) return url
        return url.replaceRange(bounds.range,
            "=w${maxOf(width, target)}-h${maxOf(height, target)}")
    }
    val square = Regex("""=s(\d+)(?=-|$)""").find(url) ?: return url
    val old = square.groupValues[1].toIntOrNull() ?: return url
    return if (old < target) url.replaceRange(square.range, "=s$target") else url
}
