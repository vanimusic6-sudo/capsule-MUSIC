package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Independent, anonymous Internet Archive Netlabels catalog.
 *
 * Search returns item identifiers rather than playable music; each identifier
 * is resolved against Archive's official metadata API. Only public audio files
 * with an explicit Creative Commons or public-domain license are offered.
 * Never pass this source to InnerTube / the YouTube audio resolver.
 */
internal object InternetArchiveMusic {
    private const val MAX_SEARCH_ITEMS = 32
    private const val MAX_METADATA_LOOKUPS = 9
    private val ident = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{1,90}")
    private val audioFile = Regex("(?i)\\.(mp3|ogg|oga|opus|m4a|flac)$")
    private val excluded = Regex("(?i)(\\bpreview\\b|\\binterview\\b|\\bpodcast\\b|\\bsample pack\\b)")
    private val itemCache = ConcurrentHashMap<String, List<ExperimentalCatalog.Track>>()

    fun browse(
        kind: ExperimentalCatalog.HomeLaneKind,
        genre: String? = null,
    ): List<ExperimentalCatalog.Track> {
        val sort = if (kind == ExperimentalCatalog.HomeLaneKind.NEW) "publicdate desc"
            else "downloads desc"
        val raw = when (kind) {
            ExperimentalCatalog.HomeLaneKind.DISCOVER ->
                "collection:netlabels AND mediatype:audio AND subject:${genre?.take(20) ?: "ambient"}"
            else -> "collection:netlabels AND mediatype:audio"
        }
        return search(raw, sort)
    }

    fun artist(artist: String): List<ExperimentalCatalog.Track> =
        search("collection:netlabels AND mediatype:audio AND creator:\"${artist.take(65).replace("\"", "")}\"",
            "downloads desc")

    private fun search(query: String, sort: String): List<ExperimentalCatalog.Track> {
        val base = "https://archive.org/advancedsearch.php"
        fun searchDocuments(filter: String) = fetchJson(
            "$base?q=${Uri.encode(filter)}&fl%5B%5D=identifier" +
                "&fl%5B%5D=format&fl%5B%5D=licenseurl" +
                "&sort%5B%5D=${Uri.encode(sort)}&rows=$MAX_SEARCH_ITEMS&output=json",
        ).optJSONObject("response")?.optJSONArray("docs")
        // Netlabels search could return metadata-only placeholders first.
        // Ask the Archive index for items containing audio file formats; if
        // the field is missing, retain the broader music-collection fallback.
        val preferred = "$query AND (format:\"VBR MP3\" OR format:\"MP3\" OR format:\"Ogg Vorbis\" OR format:\"FLAC\")"
        val docs = searchDocuments(preferred)?.takeIf { it.length() > 0 }
            ?: searchDocuments(query) ?: return emptyList()
        val tracks = mutableListOf<ExperimentalCatalog.Track>()
        var examined = 0
        for (index in 0 until minOf(docs.length(), MAX_SEARCH_ITEMS)) {
            if (examined >= MAX_METADATA_LOOKUPS || tracks.size >= 18) break
            val id = docs.optJSONObject(index)?.optString("identifier")
                ?.takeIf { ident.matches(it) } ?: continue
            examined++
            val candidates = try {
                itemCache[id] ?: readItem(id).also { if (it.isNotEmpty()) itemCache[id] = it }
            } catch (error: Exception) {
                Timber.tag("CapsuleArchive").d(error, "Archive metadata unavailable")
                emptyList()
            }
            tracks.addAll(candidates)
            if (tracks.size >= 24) break
        }
        return tracks.distinctBy { it.mediaId }.take(24)
    }

    private fun readItem(id: String): List<ExperimentalCatalog.Track> {
        val data = fetchJson("https://archive.org/metadata/$id", maxBytes = 3 * 1024 * 1024)
        val info = data.optJSONObject("metadata") ?: return emptyList()
        if (info.optString("access-restricted-item") == "true") return emptyList()
        val license = jsonString(info, "licenseurl").takeIf {
            it.startsWith("https://creativecommons.org/") ||
                it.startsWith("http://creativecommons.org/")
        }
        // No clear CC metadata -> a browsable release link, NEVER an audio
        // stream. The artist/album still belongs on the discovery Home.
        val creator = jsonString(info, "creator").ifBlank { "Netlabels" }
        val album = jsonString(info, "title").ifBlank { id }
        val files = data.optJSONArray("files") ?: JSONArray()
        val coverFile = (0 until files.length()).asSequence()
            .mapNotNull { files.optJSONObject(it) }
            .firstOrNull { img ->
                img.optString("name").matches(Regex("(?i).*(itemimage|cover|front).*\\.(jpg|jpeg|png|webp)")) &&
                    img.optString("size").toLongOrNull()?.let { it in 2_000..2_000_000 } == true
            }?.optString("name")
        val cover = coverFile?.let { "https://archive.org/download/$id/" + Uri.encode(it, "/") }
            ?: "https://archive.org/services/img/$id"
        val audio = mutableListOf<ExperimentalCatalog.Track>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            if (file.optString("private") == "true") continue
            val name = file.optString("name")
            if (name.isBlank() || name.contains("..") || !audioFile.containsMatchIn(name)) continue
            if (name.endsWith("_64kb.mp3", ignoreCase = true) ||
                name.endsWith("_spectrogram.png", ignoreCase = true)) continue
            val size = file.optString("size").toLongOrNull() ?: 0
            if (size !in 120_000L..40_000_000L) continue
            val duration = parseSeconds(file.optString("length"))
            if (duration > 1800) continue
            val trackName = name.substringAfterLast('/')
                .substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
            val title = file.optString("title").ifBlank { trackName }
            if (title.length < 3 || excluded.containsMatchIn(title)) continue
            val encodedFile = Uri.encode(name, "/")
            val stream = "https://archive.org/download/$id/$encodedFile"
            audio.add(ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.ARCHIVE,
                id = "$id/$name",
                title = if (title.equals(id, true)) album else title,
                artist = creator,
                artwork = cover,
                artworkLarge = cover,
                streamUrl = stream.takeIf { license != null },
                sourcePage = "https://archive.org/details/$id",
                durationSeconds = duration,
                license = license,
                catalogProvider = "archive",
                audioCategory = "music",
                artistId = creator,
            ))
            if (audio.size >= 3) break
        }
        if (audio.isNotEmpty()) return audio
        // Older Netlabels albums may be compressed archives or metadata-only.
        // Never present them as playable, but provide a real source page.
        return listOf(ExperimentalCatalog.Track(
            source = ExperimentalCatalog.Source.ARCHIVE,
            id = id,
            title = album,
            artist = creator,
            artwork = cover,
            artworkLarge = cover,
            streamUrl = null,
            sourcePage = "https://archive.org/details/$id",
            license = license,
            catalogProvider = "archive",
            audioCategory = "music",
            artistId = creator,
        ))
    }

    private fun jsonString(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return when (value) {
            is String -> value
            is JSONArray -> value.optString(0)
            else -> ""
        }.trim()
    }

    private fun parseSeconds(raw: String): Int {
        val seconds = raw.toDoubleOrNull()
        if (seconds != null) return seconds.toInt().coerceIn(0, 86400)
        val parts = raw.split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun fetchJson(url: String, maxBytes: Int = 1024 * 1024): JSONObject {
        val proxy = YouTube.streamProxy
        val connection = (if (proxy != null) URL(url).openConnection(proxy)
            else URL(url).openConnection()) as HttpURLConnection
        connection.connectTimeout = 6500
        connection.readTimeout = 7500
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(
            "User-Agent", "CapsuleMusic/1.0 (+https://github.com/vanimusic6-sudo/capsule-MUSIC)")
        try {
            if (connection.responseCode !in 200..299) return JSONObject()
            connection.inputStream.bufferedReader().use { input ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.length + count > maxBytes) return JSONObject()
                    out.append(buffer, 0, count)
                }
                return JSONObject(out.toString())
            }
        } finally {
            connection.disconnect()
        }
    }
}
