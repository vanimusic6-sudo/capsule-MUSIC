package com.nikhil.yt.links

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Public Spotify / SoundCloud track metadata, never external audio stream extraction.
 * Title and artist support conservative YouTube Music matching, with manual search
 * when there is no unique match. No account cookies or API tokens are sent.
 *
 * Call from Dispatchers.IO.
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
            if (title.length in 2..180) {
                val verifiedArtist = artist ?: if (external.provider == IncomingTrackLink.Provider.SPOTIFY) {
                    getText(canonical.url)?.let(::spotifyArtistFromPage)
                } else null
                return TrackInfo(title, verifiedArtist)
            }
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

    /** A public Spotify track page sometimes exposes the artist when oEmbed only has the title. */
    internal fun spotifyArtistFromPage(html: String): String? {
        val page = Jsoup.parse(html)
        val description = normalizeMetadata(
            page.selectFirst("meta[property=og:description]")?.attr("content")
                ?: page.selectFirst("meta[name=description]")?.attr("content")
                ?: ""
        )
        val patterns = listOf(
            Regex(""",\s*(?:a\s+)?song\s+by\s+(.+?)\s+on\s+Spotify\b""", RegexOption.IGNORE_CASE),
            Regex("""\bSong\s*[·•]\s*([^·•]+)\s*[·•]""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { expression ->
            expression.find(description)?.groupValues?.getOrNull(1)
                ?.let(::normalizeMetadata)
                ?.takeIf { it.length in 2..100 && !it.equals("Spotify", ignoreCase = true) }
        }
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
            connection.setRequestProperty("Accept", if (uri.path?.contains("/oembed") == true) "application/json" else "text/html,application/xhtml+xml")
            connection
        }.getOrNull()
}
