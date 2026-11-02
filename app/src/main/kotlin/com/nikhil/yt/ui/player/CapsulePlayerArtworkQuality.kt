package com.nikhil.yt.ui.player

import com.nikhil.yt.ui.component.capsuleArtworkUrl
import java.net.URI
import java.util.Locale

/**
 * Full-player artwork requests only. No 120/256px preview and no fallback to
 * YouTube's 480x360 hqdefault after a failed higher-quality thumbnail.
 *
 * YouTube video posters are not album covers; some hqdefault images only
 * exist in a tiny version. Try original-composition high-res variants first.
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
            return listOf(
                "$directory/maxresdefault.$extension",
                "$directory/hq720.$extension",
            ).distinct()
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
    return !isYoutube || decodedWidth >= 720
}
