package com.nikhil.yt.links

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Link previews, not stream extraction: Spotify / SoundCloud audio is NOT playable
 * with a YouTube media ID. Find its title, then let the listener choose the correct
 * matching item in Capsule's existing YouTube Music search screen.
 *
 * Call from Dispatchers.IO. No cookies, login tokens or user credentials are sent.
 */
internal object ExternalTrackMetadata {
    fun searchQuery(external: IncomingTrackLink.External): String? {
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
        val response = getText(endpoint + URLEncoder.encode(canonical.url, "UTF-8")) ?: return null
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val provider = json.optString("provider_name")
        if (!provider.equals(external.provider.name, ignoreCase = true)) return null
        val title = json.optString("title")
            .replace(Regex("""\s*[|]\s*(?:Spotify|SoundCloud)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // Never search for the opaque Spotify ID or play the first result blindly.
        return title.takeIf { it.length in 2..180 }
    }

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
                val buf = CharArray(12000)
                val count = reader.read(buf)
                if (count > 0) String(buf, 0, count) else null
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
