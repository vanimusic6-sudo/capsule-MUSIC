package com.nikhil.yt.discovery

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in music discovery. Never called by the normal YouTube search or audio resolver.
 * Sources must supply their own valid streaming URL; media IDs are never sent to YouTube.
 */
object ExperimentalCatalog {
    enum class Source(val title: String) {
        AUDIUS("Audius"), ARCHIVE("Internet Archive"), MIRLO("Mirlo")
    }

    data class Track(
        val source: Source,
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String?,
        val streamUrl: String?,
        val sourcePage: String,
        val durationSeconds: Int = 0,
        val license: String? = null,
        val year: String? = null,
        val genre: String? = null,
    ) {
        val mediaId: String get() = EXPERIMENTAL_MEDIA_PREFIX + source.name.lowercase() + ":" + id
        val playable: Boolean get() = streamUrl?.startsWith("https://") == true
    }

    const val EXPERIMENTAL_MEDIA_PREFIX = "capsule-open:"
    private const val CACHE_MS = 5 * 60_000L
    private data class CacheEntry(val at: Long, val tracks: List<Track>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun search(source: Source, query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(100)
        if (trimmed.isBlank()) return@withContext emptyList()
        cached("search:${source.name}:${trimmed.lowercase()}") {
            when (source) {
                Source.AUDIUS -> audius("/tracks/search?query=${enc(trimmed)}&limit=24")
                Source.ARCHIVE -> archive(trimmed)
                Source.MIRLO -> mirlo(trimmed)
            }
        }
    }

    /** Small, bounded, diversified discovery pool. Failure of one source never blocks the others. */
    suspend fun recommend(tasteArtists: List<String>): List<Track> = withContext(Dispatchers.IO) {
        cached("recommend:" + tasteArtists.take(2).joinToString("|").lowercase()) {
            val seeds = tasteArtists.asSequence()
                .map { it.trim() }.filter { it.isNotBlank() }.take(2).toList()
            val audiusPool = safely {
                audius("/tracks/trending/underground?limit=20") +
                    seeds.flatMap { audius("/tracks/search?query=${enc(it)}&limit=5") }
            }
            val archivePool = safely { archive(null) }
            val mirloPool = safely { mirlo(null) }
            val lanes = listOf(audiusPool, archivePool, mirloPool)
            val seen = mutableSetOf<String>()
            val artists = mutableMapOf<String, Int>()
            val result = mutableListOf<Track>()
            var pos = 0
            while (result.size < 18 && pos < 40) {
                for (lane in lanes) {
                    val track = lane.getOrNull(pos) ?: continue
                    if (track.title.isBlank() || track.artist.isBlank()) continue
                    if (track.durationSeconds > 1200) continue
                    val artistKey = track.artist.trim().lowercase()
                    if ((artists[artistKey] ?: 0) >= 2 || !seen.add(track.mediaId)) continue
                    artists[artistKey] = (artists[artistKey] ?: 0) + 1
                    result.add(track)
                }
                pos++
            }
            result
        }
    }

    private inline fun safely(block: () -> List<Track>): List<Track> =
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() }

    private fun cached(key: String, loader: () -> List<Track>): List<Track> {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.tracks }
        val value = loader().distinctBy { it.mediaId }
        cache[key] = CacheEntry(now, value)
        return value
    }

    private fun audius(endpoint: String): List<Track> {
        val data = requestJson("https://api.audius.co/v1" + endpoint).optJSONArray("data")
            ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val obj = data.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!obj.optBoolean("is_streamable", true) ||
                obj.optString("access").equals("purchase", ignoreCase = true)) return@mapNotNull null
            val user = obj.optJSONObject("user")
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = user?.optString("name")?.takeIf { it.isNotBlank() } ?: "Audius"
            val artwork = obj.optJSONObject("artwork")
            Track(
                source = Source.AUDIUS,
                id = id,
                title = title,
                artist = artist,
                artwork = artwork?.optString("480x480")?.takeIf { it.startsWith("https://") }
                    ?: artwork?.optString("150x150")?.takeIf { it.startsWith("https://") },
                streamUrl = "https://api.audius.co/v1/tracks/${enc(id)}/stream",
                sourcePage = obj.optString("permalink").takeIf { it.startsWith("https://") }
                    ?: "https://audius.co",
                durationSeconds = obj.optInt("duration").coerceAtLeast(0),
                genre = obj.optString("genre").ifBlank { null },
            )
        }
    }

    private fun archive(query: String?): List<Track> {
        val q = if (query.isNullOrBlank()) {
            "mediatype:audio AND collection:netlabels"
        } else {
            "mediatype:audio AND (title:(${query.take(80)}) OR creator:(${query.take(80)}))"
        }
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl[]=identifier&rows=12&output=json&sort[]=downloads+desc"
        val docs = requestJson(url).optJSONObject("response")?.optJSONArray("docs")
            ?: return emptyList()
        return (0 until docs.length()).mapNotNull { index ->
            val id = docs.optJSONObject(index)?.optString("identifier")
                ?.takeIf { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9._-]+")) }
                ?: return@mapNotNull null
            try {
                val item = requestJson("https://archive.org/metadata/${enc(id)}")
                val meta = item.optJSONObject("metadata") ?: return@mapNotNull null
                val license = meta.optString("licenseurl")
                // An Archive listing does not itself grant permission to redistribute music.
                if (!license.startsWith("https://creativecommons.org/") &&
                    !license.startsWith("http://creativecommons.org/")) return@mapNotNull null
                val files = item.optJSONArray("files") ?: return@mapNotNull null
                val file = (0 until files.length()).asSequence().mapNotNull { files.optJSONObject(it) }
                    .firstOrNull {
                        val name = it.optString("name").lowercase()
                        (name.endsWith(".mp3") || name.endsWith(".ogg") ||
                            name.endsWith(".m4a") || name.endsWith(".flac")) &&
                            !name.contains("sample") && !name.contains("preview")
                    } ?: return@mapNotNull null
                val name = file.optString("name")
                val encodedName = name.split("/").joinToString("/") { enc(it) }
                val artist = meta.optString("creator").ifBlank { "Internet Archive" }
                val title = meta.optString("title").ifBlank { name.substringBeforeLast(".") }
                Track(
                    source = Source.ARCHIVE,
                    id = id + ":" + name,
                    title = title,
                    artist = artist,
                    artwork = "https://archive.org/services/img/${enc(id)}",
                    streamUrl = "https://archive.org/download/${enc(id)}/$encodedName",
                    sourcePage = "https://archive.org/details/${enc(id)}",
                    license = license,
                    year = meta.optString("year").ifBlank { null },
                )
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }

    private fun mirlo(query: String?): List<Track> {
        // Federation contains only artists who opted in. Streaming additionally needs
        // a client key and per-listener token; do not bypass that authorization.
        val root = requestJson("https://mirlo.space/v1/sm/canimus.json?take=18")
        val found = mutableListOf<Track>()
        fun visit(node: JSONObject, artist: String, artwork: String?, depth: Int) {
            if (depth > 5 || found.size >= 90) return
            val type = node.optString("type")
            val name = node.optString("name")
            val currentArtist = if (type == "artist") name else artist
            val image = node.optJSONObject("images")?.optJSONObject("cover")
                ?.optString("src")?.takeIf { it.startsWith("https://") } ?: artwork
            val link = node.optString("url")
            if (type == "track" && link.startsWith("https://") && name.isNotBlank()) {
                found.add(
                    Track(Source.MIRLO, link, name, currentArtist.ifBlank { "Mirlo" },
                        image, null, link),
                )
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { visit(it, currentArtist, image, depth + 1) }
            }
        }
        visit(root, "", null, 0)
        val term = query?.trim()?.lowercase()
        return if (term.isNullOrEmpty()) found.take(18) else found.filter {
            term in it.title.lowercase() || term in it.artist.lowercase()
        }.take(25)
    }

    private fun enc(value: String): String = Uri.encode(value)
    private fun requestJson(url: String): JSONObject {
        require(url.startsWith("https://"))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 6500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CapsuleMusic/experimental-discovery")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("Provider HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(8192)
                val body = StringBuilder()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (body.length + count > 2 * 1024 * 1024) {
                        throw java.io.IOException("Catalog response too large")
                    }
                    body.append(buffer, 0, count)
                }
                if (body.isEmpty()) throw java.io.IOException("Empty catalog response")
                JSONObject(body.toString())
            }
        } finally { connection.disconnect() }
    }
}
