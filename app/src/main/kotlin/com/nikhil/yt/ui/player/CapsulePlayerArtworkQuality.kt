package com.nikhil.yt.ui.player

import com.nikhil.yt.ui.component.capsuleArtworkUrl
import java.net.URI
import java.util.Locale

/**
 * Full-player artwork requests only. NEVER request a low-resolution preview
 * before the full-sized result. If YouTube has no 720p+ image for a video,
 * the original poster is a *last resort*, not an intermediate flash.
 * Keep arbitrary CDNs and signed URLs untouched.
 */
internal fun capsuleFullPlayerArtworkCandidates(original: String?): List<String> {
    val url = original?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    if ('?' in url || '#' in url) return listOf(url)
    val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
    if (uri.scheme?.lowercase(Locale.US) != "https") return listOf(url)
    val host = uri.host?.lowercase(Locale.US).orEmpty()
    if (host == "i.ytimg.com" || host == "img.youtube.com") {
        val match = Regex(
            """^/(vi|vi_webp)/([A-Za-z0-9_-]{11})/(?:default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\.(jpg|webp)$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(uri.path.orEmpty())
        if (match != null) {
            val directory = url.substringBeforeLast("/")
            val extension = match.groupValues[3].lowercase(Locale.US)
            // A number of real music video posters exist only as hqdefault.
            // Retain that original as the final candidate rather than painting
            // an "empty album" glyph over a perfectly valid 480px poster.
            return (listOf(
                "$directory/maxresdefault.$extension",
                "$directory/hq720.$extension",
            ) + url).distinct()
        }
    }
    // Google Music art supports larger server-side renditions while keeping
    // crop flags; preserve the original URL for non-Google/signed providers.
    return listOfNotNull(capsuleArtworkUrl(url, 960)).distinct()
}

/**
 * YouTube may return a tiny "no maxres thumbnail" bitmap with HTTP 200.
 * Reject that as full-player artwork rather than stretching it to 960px.
 * Source images at least 720px wide are accepted, with a proportional crop.
 */
internal fun capsulePlayerPosterLargeEnough(
    url: String?,
    decodedWidth: Int,
    decodedHeight: Int,
): Boolean {
    if (url.isNullOrBlank() || decodedWidth <= 0 || decodedHeight <= 0) return false
    val isYoutube = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.US)
        host == "i.ytimg.com" || host == "img.youtube.com"
    }.getOrDefault(false)
    if (!isYoutube) return true
    // An original 480x360 hqdefault/mqdefault is valid *after* trying 720p+
    // variants. Fake YouTube "maxres unavailable" JPEGs are only ~120px.
    val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    val originalVideoPoster = path.endsWith("/hqdefault.jpg") ||
        path.endsWith("/hqdefault.webp") || path.endsWith("/sddefault.jpg") ||
        path.endsWith("/sddefault.webp") || path.endsWith("/mqdefault.jpg") ||
        path.endsWith("/mqdefault.webp")
    return if (originalVideoPoster) decodedWidth >= 320 && decodedHeight >= 180
    else decodedWidth >= 720
}
