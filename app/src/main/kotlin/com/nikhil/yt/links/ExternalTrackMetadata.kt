package com.nikhil.yt.links

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Link previews, not stream extraction: Spotify / SoundCloud audio is NOT playable
 * with a YouTube media ID. Find its title, then let the listener choose the correct
 * matching item in Capsule's existing YouTube Music search screen.
 *
 * Call from Dispatchers.IO. No cookies, login tokens or user credentials are sent.
 */
internal object ExternalTrackMetadata {
    data class TrackInfo(val title: String, val artist: String?) {
        val query: String get() = listOfNotNull(title, artist).joinToString(" ")
    }

    fun searchQuery(external: IncomingTrackLink.External): String? = trackInfo(external)?.query

    fun trackInfo(external: IncomingTrackLink.External): TrackInfo? {
        val trackUrl = if (isShortLink(external.url)) {
            resolveShortLink(external) ?: return null
        } else {
            external.url
        }
        val canonical = IncomingTrackLinks.classify(trackUrl) as? IncomingTrackLink.External ?: return null
        if (canonical.provider != external.provider) return null
        if (isShortLink(canonical.url)) return null

        val endpoint = when (external.provider) {
            IncomingTrackLink.Provider.SPOTIFY -> "https://open.spotify.com/oembed?url="
            IncomingTrackLink.Provider.SOUNDCLOUD -> "https://soundcloud.com/oembed?format=json&url="
        }
        val response = getText(endpoint + URLEncoder.encode(canonical.url, "UTF-8"))
        val json = response?.let { runCatching { JSONObject(it) }.getOrNull() }
        val provider = json?.optString("provider_name").orEmpty()
        val oembedMatchesProvider = provider.equals(external.provider.name, ignoreCase = true)
        if (oembedMatchesProvider) {
            val rawTitle = json?.optString("title").orEmpty()
            val rawAuthor = json?.optString("author_name").orEmpty()
            val artist = normalizeMetadata(rawAuthor)
                .takeIf { it.length in 2..100 && !it.equals(external.provider.name, ignoreCase = true) }
            val title = normalizeMetadata(rawTitle)
                .replace(Regex("""\s*[|]\s*(?:Spotify|SoundCloud)\s*$""", RegexOption.IGNORE_CASE), "")
                .let { title ->
                    if (artist != null && title.endsWith(" by $artist", ignoreCase = true)) {
                        title.dropLast(artist.length + 4).trim()
                    } else title
                }
            if (title.length in 2..180) return TrackInfo(title, artist)
        }
        // The oEmbed endpoint may be unavailable or may reject a public track.
        // Public page metadata is a fallback; without a confirmed artist the app
        // offers search results rather than automatically playing an unrelated cover.
        val html = getText(canonical.url) ?: return null
        val page = Jsoup.parse(html, canonical.url)
        val title = normalizeMetadata(
            page.selectFirst("meta[property=og:title]")?.attr("content")
                ?: page.selectFirst("meta[name=twitter:title]")?.attr("content")
                ?: ""
        ).replace(Regex("""\s*[|]\s*(?:Spotify|SoundCloud)\s*$""", RegexOption.IGNORE_CASE), "")
        return title.takeIf { it.length in 2..180 }?.let { TrackInfo(it, null) }
    }

    private fun normalizeMetadata(value: String): String =
        Jsoup.parse(value).text().replace(Regex("""\s+"""), " ").trim()

    private fun isShortLink(raw: String): Boolean =
        when (runCatching { URI(raw).host?.lowercase() }.getOrNull()) {
            "spotify.link", "on.soundcloud.com", "snd.sc" -> true
            else -> false
        }

    private fun resolveShortLink(external: IncomingTrackLink.External): String? {
        // Some short-link services reject HEAD; follow with GET without reading the page body.
        for (method in listOf("HEAD", "GET")) {
            val connection = open(external.url, method = method) ?: continue
            try {
                if (connection.responseCode !in 200..399) continue
                val final = IncomingTrackLinks.classify(connection.url.toString()) as? IncomingTrackLink.External
                if (final != null && final.provider == external.provider && !isShortLink(final.url)) {
                    return final.url
                }
            } catch (_: Exception) {
                // Fall back to the next method, then show a readable link error.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun getText(url: String): String? {
        val connection = open(url) ?: return null
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(2048)
                while (body.length < 32768) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, 32768 - body.length))
                    if (read < 0) break
                    if (read > 0) body.append(buffer, 0, read)
                }
                body.toString().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(rawUrl: String, method: String = "GET"): HttpURLConnection? =
        runCatching {
            val uri = URI(rawUrl)
            require(uri.scheme.equals("https", ignoreCase = true))
            val connection = URL(rawUrl).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5500
            connection.readTimeout = 5500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection
        }.getOrNull()
}
