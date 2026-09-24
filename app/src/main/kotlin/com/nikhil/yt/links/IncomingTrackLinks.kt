package com.nikhil.yt.links

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A verified source link, never a guess based on a pasted title or arbitrary domain. */
internal sealed interface IncomingTrackLink {
    data class YouTube(val videoId: String, val playlistId: String?) : IncomingTrackLink
    data class External(val provider: Provider, val url: String) : IncomingTrackLink

    enum class Provider { SPOTIFY, SOUNDCLOUD }
}

/** Pure URL parser: also accepts a link embedded in a Share sheet's descriptive text. */
internal object IncomingTrackLinks {
    private val webLink = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val spotifyId = Regex("[A-Za-z0-9]{22}")

    fun parse(raw: String): IncomingTrackLink? {
        val input = raw.trim()
        if (input.isBlank()) return null

        if (input.startsWith("spotify:track:", ignoreCase = true)) {
            val id = input.substringAfterLast(':').trim()
            if (spotifyId.matches(id)) {
                return IncomingTrackLink.External(IncomingTrackLink.Provider.SPOTIFY, "https://open.spotify.com/track/$id")
            }
        }

        return webLink.findAll(input).mapNotNull { match ->
            val url = match.value.trimEnd('.', ',', ';', ')', ']', '}', '»', '”', '!', '?')
            classify(url)
        }.firstOrNull()
    }

    /** Only accepted hostnames are eligible for resolving external metadata. */
    fun classify(raw: String): IncomingTrackLink? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        val first = parts.firstOrNull()?.lowercase()
        return when (host) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com" -> {
                val id = when (first) {
                    "watch" -> queryValue(uri.rawQuery, "v")
                    "shorts", "live", "v", "embed" -> parts.getOrNull(1)
                    else -> null
                }
                id?.takeIf(videoId::matches)?.let {
                    IncomingTrackLink.YouTube(it, queryValue(uri.rawQuery, "list"))
                }
            }
            "youtu.be", "www.youtu.be" ->
                parts.firstOrNull()?.takeIf(videoId::matches)?.let {
                    IncomingTrackLink.YouTube(it, queryValue(uri.rawQuery, "list"))
                }
            "open.spotify.com", "www.spotify.com", "spotify.com" -> {
                val offset = if (first?.startsWith("intl-") == true) 1 else 0
                val id = parts.getOrNull(offset + 1)
                if (parts.getOrNull(offset)?.lowercase() == "track" && id != null && spotifyId.matches(id)) {
                    IncomingTrackLink.External(IncomingTrackLink.Provider.SPOTIFY, "https://open.spotify.com/track/$id")
                } else null
            }
            "spotify.link" -> if (parts.isNotEmpty()) {
                IncomingTrackLink.External(IncomingTrackLink.Provider.SPOTIFY, raw)
            } else null
            "soundcloud.com", "www.soundcloud.com", "m.soundcloud.com" -> {
                // SoundCloud uses /artist/track; do not pretend that sets, profiles or likes are tracks.
                val excluded = setOf("sets", "likes", "reposts", "tracks", "albums", "discover", "you", "stream", "search")
                if (parts.size >= 2 && first !in excluded && parts[1].lowercase() !in excluded) {
                    IncomingTrackLink.External(IncomingTrackLink.Provider.SOUNDCLOUD, raw)
                } else null
            }
            "on.soundcloud.com", "snd.sc" -> if (parts.isNotEmpty()) {
                IncomingTrackLink.External(IncomingTrackLink.Provider.SOUNDCLOUD, raw)
            } else null
            else -> null
        }
    }

    private fun queryValue(query: String?, name: String): String? =
        query.orEmpty().split('&').firstNotNullOfOrNull { entry ->
            val key = entry.substringBefore('=')
            if (key != name || '=' !in entry) return@firstNotNullOfOrNull null
            runCatching {
                URLDecoder.decode(entry.substringAfter('='), StandardCharsets.UTF_8.name())
            }.getOrNull()?.takeIf(String::isNotBlank)
        }
}
