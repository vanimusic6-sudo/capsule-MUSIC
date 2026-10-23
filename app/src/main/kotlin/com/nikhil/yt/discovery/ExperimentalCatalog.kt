package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
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
        AUDIUS("Audius"), OPENVERSE("Openverse")
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
        /** Original collection indexed by Openverse (e.g. jamendo). */
        val catalogProvider: String? = null,
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
                Source.OPENVERSE -> openverse(query = trimmed)
            }
        }
    }

    /**
     * Audius remains an independent official catalog. Openverse searches the
     * Creative Commons audio index, including tracks indexed from Jamendo:
     * no direct Jamendo API credentials, HTML scraping or accounts.
     */
    suspend fun recommend(tasteArtists: List<String>): List<Track> = coroutineScope {
        val lanes = Source.entries.map { source ->
            async(Dispatchers.IO) { discoveryFrom(source, tasteArtists) }
        }
        ExperimentalRanking.select(
            tracks = lanes.flatMap { it.await() },
            dayKey = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay(),
        )
    }

    /** Each provider is independent. The home shelf paints each result as it arrives. */
    suspend fun discoveryFrom(source: Source, tasteArtists: List<String>): List<Track> =
        withContext(Dispatchers.IO) {
            cached("lane:${source.name}:" + tasteArtists.take(2).joinToString("|").lowercase()) {
                safely {
                    when (source) {
                        Source.AUDIUS -> audius("/tracks/trending/underground?limit=25") +
                            tasteArtists.take(2).filter(String::isNotBlank).flatMap {
                                safely { audius("/tracks/search?query=${enc(it)}&limit=4") }
                            }
                        Source.OPENVERSE -> {
                            // Rotate the *genre*, not the listening history: niche
                            // artists need not share names with mainstream favorites.
                            val themes = listOf(
                                "ambient", "electronic", "indie", "jazz",
                                "instrumental", "downtempo", "experimental", "lofi",
                            )
                            val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                                .toEpochDay()
                            val theme = themes[Math.floorMod(day, themes.size.toLong()).toInt()]
                            openverse(query = theme, sourceFilter = "jamendo")
                        }
                    }
                }
            }
        }

    suspend fun artistTracks(source: Source, artist: String, id: String?): List<Track> =
        withContext(Dispatchers.IO) {
            cached("artist:${source.name}:${id.orEmpty()}:${artist.lowercase()}") {
                when (source) {
                    Source.AUDIUS -> if (!id.isNullOrBlank() &&
                        id.matches(Regex("[A-Za-z0-9_-]+"))) {
                        audius("/users/${enc(id)}/tracks?limit=40")
                    } else audius("/tracks/search?query=${enc(artist)}&limit=35")
                        .filter { it.artist.equals(artist, true) }
                    Source.OPENVERSE -> openverse(
                        creator = artist,
                        sourceFilter = id?.takeIf { it.matches(Regex("[a-z_]{2,30}")) },
                    )
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
        if (value.isNotEmpty()) cache[key] = CacheEntry(now, value)
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

    /** Official anonymous Openverse /v1/audio search (rate limited by provider).
     *
     * It aggregates original CC audio releases from Jamendo and other catalogs.
     * We never talk to Jamendo directly and never claim to own the recording.
     * A genuine license URL and a HTTPS original audio URL are required for
     * playback; metadata-only records remain viewable on their original page.
     */
    private fun openverse(
        query: String? = null,
        creator: String? = null,
        sourceFilter: String? = null,
    ): List<Track> {
        val args = mutableListOf(
            "page_size=20",
            "filter_dead=true",
        )
        if (!query.isNullOrBlank()) args.add("q=${enc(query.take(100))}")
        else if (!creator.isNullOrBlank()) args.add("creator=${enc(creator.take(100))}")
        else return emptyList()
        if (!sourceFilter.isNullOrBlank()) args.add("source=${enc(sourceFilter)}")
        val result = requestOpenverse("https://api.openverse.org/v1/audio/?"
            + args.joinToString("&"))
        val items = result.optJSONArray("results") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val obj = items.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return@mapNotNull null
            if (obj.optBoolean("mature", false)) return@mapNotNull null
            if (obj.optString("category") == "sound_effect") return@mapNotNull null
            val title = obj.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val artist = obj.optString("creator").ifBlank { "Unknown artist" }
            val licenseUrl = obj.optString("license_url").takeIf {
                it.startsWith("https://creativecommons.org/") ||
                    it.startsWith("http://creativecommons.org/")
            }
            val stream = obj.optString("url").takeIf { it.startsWith("https://") }
                ?.takeIf { it.length < 2048 }
            // A public index does not establish playback permission by itself.
            // Respect original publisher restrictions and expose the license.
            val mayStream = licenseUrl != null && obj.optString("license").isNotBlank()
            val provider = obj.optString("source").lowercase()
            val genres = obj.optJSONArray("genres")
            val genre = (0 until (genres?.length() ?: 0))
                .mapNotNull { genres?.optString(it)?.takeIf(String::isNotBlank) }
                .joinToString(", ").ifBlank { null }
            val tags = obj.optJSONArray("tags")
            val tagGenre = (0 until (tags?.length() ?: 0))
                .mapNotNull { tags?.optJSONObject(it)?.optString("name") }
                .take(8).joinToString(", ")
            val album = obj.optJSONObject("audio_set")
            // Prefer small provider-hosted album art, avoiding an API request
            // for *each* thumbnail under Openverse anonymous throttling.
            val cover = album?.optString("url")?.takeIf {
                it.startsWith("https://")
            } ?: obj.optString("thumbnail").takeIf { it.startsWith("https://") }
            val creatorPage = obj.optString("creator_url").takeIf {
                it.startsWith("https://")
            }
            val original = obj.optString("foreign_landing_url").takeIf {
                it.startsWith("https://")
            } ?: "https://api.openverse.org/v1/audio/${enc(id)}/"
            val millis = obj.optLong("duration", 0L)
            Track(
                source = Source.OPENVERSE,
                id = id,
                title = title,
                artist = artist,
                artwork = cover,
                artworkLarge = cover,
                streamUrl = if (mayStream) stream else null,
                sourcePage = original,
                durationSeconds = (millis / 1000L).coerceIn(0, 86400).toInt(),
                license = licenseUrl,
                genre = genre ?: tagGenre.ifBlank { null },
                artistId = provider.takeIf(String::isNotBlank),
                artistPage = creatorPage,
                catalogProvider = provider.takeIf(String::isNotBlank),
            )
        }.distinctBy { it.id }
    }

    /** Anonymous Openverse access: proactively space calls to avoid HTTP 429. */
    private val openverseLock = Any()
    private var openverseLastRequest = 0L
    private fun requestOpenverse(url: String): JSONObject {
        synchronized(openverseLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val pause = 1200L - (now - openverseLastRequest)
            if (pause in 1..1200L) Thread.sleep(pause)
            openverseLastRequest = android.os.SystemClock.elapsedRealtime()
        }
        return requestJson(url)
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
