package com.nikhil.yt.links

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.jsoup.Jsoup

/** Resolves only the recognised SK Lane song host and already-supported music providers. */
internal sealed interface SmartTrackResolution {
    data class Direct(val link: IncomingTrackLink) : SmartTrackResolution
    data class Search(val query: String) : SmartTrackResolution
}

internal object SmartTrackLinkResolver {
    private const val MAX_REDIRECTS = 4
    private const val MAX_HTML_CHARS = 262144

    /** Network operations: call from Dispatchers.IO, never Compose/main thread. */
    fun resolve(rawUrl: String): SmartTrackResolution? {
        if (IncomingTrackLinks.classify(rawUrl) !is IncomingTrackLink.SmartLink) return null
        var current = rawUrl

        repeat(MAX_REDIRECTS + 1) {
            val parsed = IncomingTrackLinks.classify(current) ?: return null
            when (parsed) {
                is IncomingTrackLink.YouTube, is IncomingTrackLink.External ->
                    return SmartTrackResolution.Direct(parsed)
                is IncomingTrackLink.SmartLink -> Unit
            }

            val connection = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
            }.getOrNull() ?: return null

            val next = try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: return null
                        val target = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                            ?: return null
                        // Do not follow arbitrary redirect hosts or non-HTTPS destinations.
                        if (!target.startsWith("https://", ignoreCase = true)) return null
                        if (IncomingTrackLinks.classify(target) == null) return null
                        target
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(4096)
                            while (result.length < MAX_HTML_CHARS) {
                                val amount = reader.read(buffer, 0, minOf(buffer.size, MAX_HTML_CHARS - result.length))
                                if (amount < 0) break
                                result.append(buffer, 0, amount)
                            }
                            result.toString()
                        }
                        return parseLandingPage(html, current)
                    }
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
            current = next
        }
        return null
    }

    /**
     * Pure HTML interpretation: prefer one unambiguous YouTube recording ID.
     * If a smart link lists multiple versions, fall back to the public song title
     * instead of playing a random upload.
     */
    fun parseLandingPage(html: String, url: String): SmartTrackResolution? {
        if (IncomingTrackLinks.classify(url) !is IncomingTrackLink.SmartLink) return null
        val page = Jsoup.parse(html, url)
        val candidates = page.select(
            "a[href], link[href], meta[property=og:video:url], meta[property=music:song], meta[name=twitter:player]"
        ).mapNotNull { element ->
            val raw = if (element.hasAttr("href")) element.absUrl("href") else element.attr("content")
            val absolute = runCatching { URI(url).resolve(raw).toString() }.getOrNull()
                ?: return@mapNotNull null
            IncomingTrackLinks.classify(absolute)
        }

        val youtube = candidates.filterIsInstance<IncomingTrackLink.YouTube>()
            .distinctBy { it.videoId }
        if (youtube.size == 1) return SmartTrackResolution.Direct(youtube.single())

        val external = candidates.filterIsInstance<IncomingTrackLink.External>()
            .distinctBy { it.provider to it.url }
        // Spotify/SoundCloud IDs are not directly playable in a YouTube-based player.
        // Passing their URL into the existing metadata/search path keeps this distinction.
        if (youtube.isEmpty() && external.size == 1) {
            return SmartTrackResolution.Direct(external.single())
        }

        val ogTitle = page.selectFirst("meta[property=og:title]")?.attr("content")
            ?: page.selectFirst("meta[name=twitter:title]")?.attr("content")
        val title = ogTitle.orEmpty()
            .replace(Regex("""\s*[|–-]\s*(?:SK Lane|Music SK Lane)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val generic = setOf("sk lane", "music sk lane", "music", "song", "listen now")
        return title.takeIf { it.length in 3..160 && it.lowercase() !in generic }
            ?.let { SmartTrackResolution.Search(it) }
    }
}
