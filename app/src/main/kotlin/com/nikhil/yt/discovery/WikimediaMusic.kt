package com.nikhil.yt.discovery

import android.net.Uri
import android.text.Html
import com.nikhil.yt.innertube.YouTube
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

/**
 * Actual Commons files rather than an Openverse index entry.
 *
 * Search only File: audio pages, resolve their official imageinfo.url and
 * Creative Commons metadata. No third-party preview URLs or API tokens.
 */
internal object WikimediaMusic {
    private val audioExt = Regex("(?i)\\.(mp3|ogg|oga|opus|flac|aac|m4a)$")
    private val noise = listOf("pronunciation", "audio pronunciation", "spoken word",
        "speech recording", "test audio", "test tone", "dictation", "sound effect",
        "letter of the alphabet", "phonetic")
    /** Commons artist metadata is separate from Openverse creator indexing. */
    fun artist(name: String): List<ExperimentalCatalog.Track> {
        val query = name.trim().take(60)
        if (query.isBlank() || query.equals("Wikimedia Commons", true)) return emptyList()
        val candidates = browse(query)
        val matched = candidates.filter { it.artist.equals(query, true) ||
            it.artist.contains(query, ignoreCase = true) }
        return matched.ifEmpty { candidates }
    }

    fun browse(query: String): List<ExperimentalCatalog.Track> {
        val q = "${query.trim().take(45)} filetype:audio"
        val url = "https://commons.wikimedia.org/w/api.php" +
            "?action=query&generator=search&gsrnamespace=6&gsrlimit=32" +
            "&gsrsearch=${Uri.encode(q)}&prop=imageinfo" +
            "&iiprop=url%7Cmime%7Csize%7Cextmetadata&iiurlwidth=320&format=json&maxlag=5"
        val pages = fetch(url).optJSONObject("query")?.optJSONObject("pages")
            ?: return emptyList()
        val tracks = mutableListOf<ExperimentalCatalog.Track>()
        for (key in pages.keys()) {
            val page = pages.optJSONObject(key) ?: continue
            val title = page.optString("title")
            if (!title.startsWith("File:")) continue
            val file = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (!file.optString("mime").startsWith("audio/")) continue
            val stream = file.optString("url").takeIf {
                it.startsWith("https://upload.wikimedia.org/") &&
                    audioExt.containsMatchIn(it.substringBefore('?'))
            } ?: continue
            val length = file.optLong("size")
            if (length !in 120_000L..40_000_000L) continue
            val name = title.removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            if (name.length < 4 || noise.any { name.contains(it, ignoreCase = true) }) continue
            val meta = file.optJSONObject("extmetadata") ?: continue
            val license = meta.optJSONObject("LicenseUrl")?.optString("value").orEmpty()
                .takeIf { it.startsWith("https://creativecommons.org/") ||
                    it.startsWith("http://creativecommons.org/") }
                ?: if (meta.optJSONObject("LicenseShortName")?.optString("value")
                        ?.contains("public domain", true) == true)
                    "https://creativecommons.org/publicdomain/mark/1.0/" else continue
            val artistHtml = meta.optJSONObject("Artist")?.optString("value").orEmpty()
            val creator = Html.fromHtml(artistHtml, Html.FROM_HTML_MODE_LEGACY)
                .toString().replace(Regex("\\s+"), " ").trim()
                .take(90).ifBlank { "Wikimedia Commons" }
            // Commons generates thumbnails for some audio uploads. Keep only
            // actual image thumbnails, never try to decode the audio as artwork.
            val artwork = file.optString("thumburl").takeIf {
                it.startsWith("https://upload.wikimedia.org/") &&
                    !audioExt.containsMatchIn(it.substringBefore('?'))
            }
            val artistLink = Regex("https://commons\\.wikimedia\\.org/wiki/User:[^\\s\\\"'<>]+")
                .find(artistHtml)?.value
            tracks.add(ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.OPENVERSE,
                id = "commons:${page.optLong("pageid")}",
                title = name,
                artist = creator,
                artwork = artwork,
                artworkLarge = artwork,
                streamUrl = stream,
                sourcePage = "https://commons.wikimedia.org/wiki/${Uri.encode(title.replace(' ', '_'), ":")}",
                license = license,
                catalogProvider = "wikimedia_audio",
                audioCategory = "music",
                artistId = "wikimedia_audio",
                artistPage = artistLink
                    ?: "https://commons.wikimedia.org/w/index.php?search=" +
                        Uri.encode(creator) + "&title=Special:MediaSearch&type=audio",
            ))
            if (tracks.size >= 22) break
        }
        return tracks
    }

    private fun fetch(url: String): JSONObject {
        val proxy = YouTube.streamProxy
        val connection = (if (proxy != null) URL(url).openConnection(proxy)
            else URL(url).openConnection()) as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 7000
        connection.setRequestProperty(
            "User-Agent", "CapsuleMusic/1.0 (+https://github.com/vanimusic6-sudo/capsule-MUSIC)")
        try {
            if (connection.responseCode !in 200..299) return JSONObject()
            connection.inputStream.bufferedReader().use {
                val out = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val len = it.read(buf)
                    if (len < 0) break
                    if (out.length + len > 1_300_000) return JSONObject()
                    out.append(buf, 0, len)
                }
                return JSONObject(out.toString())
            }
        } catch (e: Exception) {
            Timber.tag("CapsuleCommons").d(e, "Commons catalog unavailable")
            return JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}
