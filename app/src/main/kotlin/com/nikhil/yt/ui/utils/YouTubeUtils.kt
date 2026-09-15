/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */


package com.nikhil.yt.ui.utils

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    "https://lh3\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*".toRegex()
        .matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()) {
        return "$this-s${width ?: height}"
    }
    return this
}

private val ArtistArtworkHost = Regex("^https://(?:lh[0-9]+\\.googleusercontent\\.com|yt3\\.(?:ggpht\\.com|googleusercontent\\.com))/")
private val ArtistArtworkSizing = Regex("=(?:w[0-9]+|s[0-9]+)[^/]*$")

/** Ask Google's image service for the complete portrait, without a square/face crop. */
internal fun String.artistPortraitUrl(maxSize: Int = 1600): String {
    require(maxSize > 0)
    val supported = ArtistArtworkHost.containsMatchIn(this)
    if (!supported || contains('?')) return this
    val original = replace(ArtistArtworkSizing, "")
    return "$original=s$maxSize"
}
