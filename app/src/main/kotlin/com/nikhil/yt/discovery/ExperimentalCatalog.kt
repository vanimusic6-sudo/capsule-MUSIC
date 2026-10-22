package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
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
        val artistId: String? = null,
        val artistPage: String? = null,
        val artworkLarge: String? = null,
    ) {
        val mediaId: String get() = EXPERIMENTAL_MEDIA_PREFIX + source.name.lowercase() + ":" + id
        val playable: Boolean get() = streamUrl?.startsWith("https://") == true
    }

    const val EXPERIMENTAL_MEDIA_PREFIX = "capsule-open:"
    private const val CACHE_MS = 15 * 60_000L
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

    /**
     * Taste is a hint, not a mandatory match: underground charts remain useful
     * even when the listener has no overlap with small independent catalogs.
     * Requests are concurrent; a slow Archive response cannot block Audius.
     */
    suspend fun recommend(tasteArtists: List<String>): List<Track> = coroutineScope {
        val seeds = tasteArtists.map(String::trim).filter(String::isNotBlank).take(2)
        val audius = async(Dispatchers.IO) {
            safely {
                audius("/tracks/trending/underground?limit=24") +
                    seeds.flatMap { audius("/tracks/search?query=${enc(it)}&limit=4") }
            }
        }
        val archive = async(Dispatchers.IO) { safely { archive(null) } }
        val mirlo = async(Dispatchers.IO) { safely { mirlo(null) } }
        ExperimentalRanking.select(
            tracks = audius.await() + archive.await() + mirlo.await(),
            dayKey = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay(),
        )
    }

    /** Each lane is independent; home can render Audius before Archive/Mirlo finish. */
    suspend fun discoveryFrom(source: Source, tasteArtists: List<String>): List<Track> =
        withContext(Dispatchers.IO) {
            cached("lane:${source.name}:" + tasteArtists.take(2).joinToString("|").lowercase()) {
                safely {
                    when (source) {
                        Source.AUDIUS -> {
                            audius("/tracks/trending/underground?limit=25") +
                                tasteArtists.take(2).filter { it.isNotBlank() }.flatMap {
                                    audius("/tracks/search?query=${enc(it)}&limit=4")
                                }
                        }
                        Source.ARCHIVE -> archive(null)
                        Source.MIRLO -> mirlo(null)
                    }
                }
            }
        }

    suspend fun artistTracks(source: Source, artist: String, id: String?): List<Track> =
        withContext(Dispatchers.IO) {
            cached("artist:${source.name}:${id.orEmpty()}:${artist.lowercase()}") {
                when (source) {
                    Source.AUDIUS -> if (!id.isNullOrBlank() && id.matches(Regex("[A-Za-z0-9_-]+"))) {
                        audius("/users/${enc(id)}/tracks?limit=40")
                    } else audius("/tracks/search?query=${enc(artist)}&limit=35")
                        .filter { it.artist.equals(artist, true) }
                    Source.ARCHIVE -> archive(artist, creatorOnly = true)
                    Source.MIRLO -> mirlo(artist)
                }
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

    private fun image(obj: JSONObject?, size: Int): String? =
        obj?.let {
            it.optString("${size}x$size")
                .ifBlank { it.optString("_${size}x$size") }
                .takeIf { url -> url.startsWith("https://") }
        }

    private fun audius(endpoint: String): List<Track> {
        val data = requestJson("https://api.audius.co/v1" + endpoint).optJSONArray("data")
            ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val access = item.optJSONObject("access")
            if (!item.optBoolean("is_streamable", true) ||
                access?.optBoolean("stream", true) == false ||
                item.optString("access").equals("purchase", true)) return@mapNotNull null
            val owner = item.optJSONObject("user")
            val handle = owner?.optString("handle").orEmpty()
            val ownerName = owner?.optString("name")?.takeIf { it.isNotBlank() } ?: "Audius"
            val photo = item.optJSONObject("artwork")
            Track(
                source = Source.AUDIUS,
                id = id,
                title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null,
                artist = ownerName,
                // A 54dp search result must not download a 480px original cover.
                artwork = image(photo, 150) ?: image(photo, 480),
                artworkLarge = image(photo, 480) ?: image(photo, 150),
                streamUrl = "https://api.audius.co/v1/tracks/${enc(id)}/stream",
                sourcePage = item.optString("permalink").takeIf { it.startsWith("https://") }
                    ?: "https://audius.co",
                durationSeconds = item.optInt("duration").coerceAtLeast(0),
                genre = item.optString("genre").ifBlank { null },
                artistId = owner?.optString("id")?.takeIf { it.isNotBlank() },
                artistPage = handle.takeIf { it.isNotBlank() }
                    ?.let { "https://audius.co/${enc(it)}" },
            )
        }
    }

    private fun archive(query: String?, creatorOnly: Boolean = false): List<Track> {
        val escaped = query?.trim()?.replace(Regex("[^\\p{L}\\p{N} _-]"), " ")
            ?.replace(Regex("\\s+"), " ")?.take(70)
        val clause = if (escaped.isNullOrBlank()) {
            "collection:netlabels"
        } else if (creatorOnly) {
            "creator:(\"$escaped\")"
        } else {
            "(title:(\"$escaped\") OR creator:(\"$escaped\"))"
        }
        val base = "mediatype:audio AND $clause"
        // Search for CC-labelled records first, but still verify each item's metadata:
        // some Archive search indexes do not expose the licenseurl field.
        fun results(q: String): List<String> {
            val url = "https://archive.org/advancedsearch.php?q=${enc(q)}" +
                "&fl[]=identifier&rows=22&output=json&sort[]=downloads+desc"
            val docs = requestJson(url).optJSONObject("response")?.optJSONArray("docs")
                ?: return emptyList()
            return (0 until docs.length()).mapNotNull {
                docs.optJSONObject(it)?.optString("identifier")
                    ?.takeIf { id -> id.matches(Regex("[A-Za-z0-9._-]+")) }
            }
        }
        val licensedIds: List<String> = try {
            results("$base AND licenseurl:http*")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Timber.tag("CapsuleDiscovery").w(error, "Archive licensed search failed")
            emptyList()
        }
        val candidates = if (licensedIds.isNotEmpty()) licensedIds else results(base)
        // Limit remote metadata reads; a single broken item must not poison the page.
        // Archive is large: each item commonly contains multiple derivative audio files.
        return candidates.take(12).mapNotNull { id ->
            try {
                val root = requestJson("https://archive.org/metadata/${enc(id)}")
                val meta = root.optJSONObject("metadata") ?: return@mapNotNull null
                val license = meta.optString("licenseurl")
                if (!license.startsWith("https://creativecommons.org/") &&
                    !license.startsWith("http://creativecommons.org/")) return@mapNotNull null
                val files = root.optJSONArray("files") ?: return@mapNotNull null
                val file = (0 until files.length()).asSequence()
                    .mapNotNull { files.optJSONObject(it) }
                    .filter { it.optString("private") != "true" }
                    .firstOrNull {
                        val name = it.optString("name").lowercase()
                        (name.endsWith(".mp3") || name.endsWith(".ogg") ||
                            name.endsWith(".m4a") || name.endsWith(".flac")) &&
                            !name.contains("sample") && !name.contains("preview")
                    } ?: return@mapNotNull null
                val fileName = file.optString("name")
                if (fileName.isBlank()) return@mapNotNull null
                val creator = meta.optString("creator").ifBlank { "Internet Archive" }
                val title = file.optString("title").ifBlank {
                    meta.optString("title").ifBlank { fileName.substringBeforeLast(".") }
                }
                val nameUrl = fileName.split("/").joinToString("/") { enc(it) }
                val creatorSearch = "https://archive.org/search?query=" +
                    enc("mediatype:audio AND creator:(\"$creator\")")
                Track(
                    source = Source.ARCHIVE,
                    id = "$id:$fileName",
                    title = title,
                    artist = creator,
                    artwork = "https://archive.org/services/img/${enc(id)}",
                    streamUrl = "https://archive.org/download/${enc(id)}/$nameUrl",
                    sourcePage = "https://archive.org/details/${enc(id)}",
                    artistId = creator,
                    artistPage = creatorSearch,
                    license = license,
                    year = meta.optString("year").ifBlank { meta.optString("date").take(4) },
                    durationSeconds = file.optString("length").toDoubleOrNull()?.toInt() ?: 0,
                )
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Timber.tag("CapsuleDiscovery").d(error, "Archive item unavailable")
                null
            }
        }
    }

    private fun mirlo(query: String?): List<Track> {
        val matches = mutableListOf<Track>()
        var succeeded = false
        val needle = query?.trim()?.lowercase().orEmpty()
        // Canimus has documented offset pagination, not a server-side text query.
        // Fetch small pages (large artist trees can exceed response limits), and
        // inspect several pages instead of always searching only the first 18 artists.
        for (page in 0 until (if (needle.isEmpty()) 4 else 10)) {
            val root = try {
                requestJson("https://mirlo.space/v1/sm/canimus.json?skip=${page * 3}&take=3",
                    maxBytes = 4 * 1024 * 1024)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Timber.tag("CapsuleDiscovery").w(error, "Mirlo page %d failed", page)
                if (!succeeded) throw error
                break
            }
            succeeded = true
            val children = root.optJSONArray("children") ?: continue
            if (children.length() == 0) break
            fun collect(node: JSONObject, owner: String, ownerPage: String?, cover: String?, depth: Int) {
                if (depth > 5) return
                val type = node.optString("type").lowercase()
                val name = node.optString("name")
                val nodeUrl = node.optString("url")
                val currentArtist = if (type == "artist") name else owner
                val currentPage = if (type == "artist" && nodeUrl.startsWith("https://"))
                    nodeUrl else ownerPage
                val img = node.optJSONObject("images")?.optJSONObject("cover")
                    ?.optString("src")?.takeIf { it.startsWith("https://") } ?: cover
                if (type == "track" && nodeUrl.startsWith("https://") && name.isNotBlank() &&
                    (needle.isEmpty() || listOf(name, currentArtist).any {
                        it.contains(needle, ignoreCase = true)
                    })
                ) {
                    matches.add(
                        Track(
                            source = Source.MIRLO,
                            id = nodeUrl,
                            title = name,
                            artist = currentArtist.ifBlank { "Mirlo" },
                            artwork = img,
                            streamUrl = null, // Official client-key + per-user token required.
                            sourcePage = nodeUrl,
                            durationSeconds = node.optDouble("duration", 0.0).toInt(),
                            artistId = currentPage,
                            artistPage = currentPage,
                        ),
                    )
                }
                val nested = node.optJSONArray("children") ?: return
                for (i in 0 until nested.length()) nested.optJSONObject(i)?.let {
                    collect(it, currentArtist, currentPage, img, depth + 1)
                }
            }
            for (i in 0 until children.length()) children.optJSONObject(i)?.let {
                collect(it, "", null, null, 0)
            }
            if (matches.size >= 32) break
        }
        return matches.distinctBy { it.mediaId }.take(32)
    }

    private fun enc(value: String): String = Uri.encode(value)
    private fun requestJson(url: String, maxBytes: Int = 2 * 1024 * 1024): JSONObject {
        require(url.startsWith("https://"))
        // Respect Capsule's configured network proxy, especially when Archive/Mirlo
        // are unreachable on networks where Audius still works.
        val networkProxy = YouTube.streamProxy
        val connection = (URL(url).openConnection(networkProxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
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
                    if (body.length + count > maxBytes) {
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
