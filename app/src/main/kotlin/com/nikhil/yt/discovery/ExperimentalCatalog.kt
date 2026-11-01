package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        OPENVERSE("Openverse"), ARCHIVE("Internet Archive"), CCMIXTER("ccMixter"), RADIO("Open Radio"), FUNKWHALE("Funkwhale")
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
        /** Openverse audio category (music, sound_effect, pronunciation, etc.). */
        val audioCategory: String? = null,
        /** Actual provider statistics, when supplied (for example ccMixter votes). */
        val playCount: Long = 0,
        val favoriteCount: Long = 0,
        val repostCount: Long = 0,
        /** Openverse indexes candidate files; a candidate URL is not a working stream. */
        val streamCandidates: List<String> = emptyList(),
    ) {
        val mediaId: String get() = EXPERIMENTAL_MEDIA_PREFIX + source.name.lowercase() + ":" + id
        val playable: Boolean get() = streamUrl?.startsWith("https://") == true
        val downloadUrl: String? get() = streamUrl?.takeIf { playable && !license.isNullOrBlank() }
    }

    /** Independent sections for an actual provider Home, not a single discovery carousel. */
    enum class HomeLaneKind { TRENDING, NEW, DISCOVER, FOR_YOU }
    data class HomeLane(
        val source: HomeFeedSource, val kind: HomeLaneKind, val tracks: List<Track>,
        /** Multiple curated shelves can share a kind and still have stable keys. */
        val titleRes: Int? = null,
    )

    private val playbackTracks = ConcurrentHashMap<String, Track>()
    fun registerPlayable(track: Track) {
        if (track.playable) {
            if (playbackTracks.size > 256) playbackTracks.clear()
            playbackTracks[track.mediaId] = track
        }
    }
    fun playingTrack(mediaId: String): Track? = playbackTracks[mediaId]

    const val EXPERIMENTAL_MEDIA_PREFIX = "capsule-open:"
    private const val CACHE_MS = 15 * 60_000L
    private data class CacheEntry(val at: Long, val tracks: List<Track>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun search(source: Source, query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(100)
        if (trimmed.isBlank()) return@withContext emptyList()
        cached("search:${source.name}:${trimmed.lowercase()}") {
            when (source) {
                Source.OPENVERSE -> validateSearchTracks(openverse(query = trimmed))
                Source.ARCHIVE -> InternetArchiveMusic.artist(trimmed)
                Source.CCMIXTER -> CCMixterMusic.search(trimmed)
                Source.RADIO -> RadioBrowserMusic.search(trimmed)
                Source.FUNKWHALE -> FunkwhaleMusic.search(trimmed)
            }
        }
    }

    /** No unselected provider may start a background recommendation request. */
    suspend fun recommend(tasteArtists: List<String>): List<Track> = emptyList()

    suspend fun discoveryFrom(source: Source, tasteArtists: List<String>): List<Track> =
        when (source) {
            Source.OPENVERSE -> discoveryFromHome(HomeFeedSource.JAMENDO, tasteArtists)
            Source.ARCHIVE -> discoveryFromHome(HomeFeedSource.ARCHIVE, tasteArtists)
            Source.CCMIXTER -> discoveryFromHome(HomeFeedSource.CCMIXTER, tasteArtists)
            Source.RADIO -> emptyList()
            Source.FUNKWHALE -> discoveryFromHome(HomeFeedSource.FUNKWHALE, tasteArtists)
        }

    /**
     * Source-selective Home discovery. Every provider has its own cache key;
     * one unreachable upstream never stops other providers from appearing.
     * Use only the official anonymous Openverse API for all three origins.
     */
    suspend fun discoveryFromHome(
        source: HomeFeedSource,
        tasteArtists: List<String>,
    ): List<Track> = withContext(Dispatchers.IO) {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()
        cached("home:${source.id}:$day:" + tasteArtists.take(2).joinToString("|").lowercase()) {
            when (source) {
                HomeFeedSource.YOUTUBE -> emptyList()
                HomeFeedSource.ARCHIVE -> InternetArchiveMusic.browse(HomeLaneKind.DISCOVER)
                HomeFeedSource.CCMIXTER -> CCMixterMusic.browse(HomeLaneKind.DISCOVER)
                HomeFeedSource.RADIO -> RadioBrowserMusic.browse(HomeLaneKind.TRENDING)
                HomeFeedSource.FUNKWHALE -> FunkwhaleMusic.browse(HomeLaneKind.DISCOVER)
                else -> {
                    val themes = when (source) {
                        HomeFeedSource.JAMENDO -> listOf(
                            "indie", "electronic", "jazz", "ambient", "rock", "experimental",
                            "instrumental", "downtempo",
                        )
                        HomeFeedSource.WIKIMEDIA -> listOf(
                            "folk music", "traditional song", "vintage jazz",
                            "classical music", "instrumental music", "traditional music",
                        )
                        HomeFeedSource.FREESOUND -> listOf(
                            "melodic music", "piano music", "ambient music",
                            "instrumental", "guitar music", "electronic music",
                        )
                        else -> emptyList()
                    }
                    val theme = themes[Math.floorMod(day, themes.size.toLong()).toInt()]
                    val upstream = requireNotNull(source.openverseId)
                    openverse(query = theme, sourceFilter = upstream)
                }
            }
        }
    }

    /** Home catalog for a selected source. Queries never touch the YouTube audio core.
     * Preserve metadata-only Openverse results, whose original release page is still
     * useful even when a publisher doesn't expose a safe direct HTTPS stream.
     */
    suspend fun homeLanes(
        source: HomeFeedSource,
        tasteArtists: List<String>,
        onLane: suspend (HomeLane) -> Unit = {},
    ): List<HomeLane> = withContext(Dispatchers.IO) {
            if (source == HomeFeedSource.YOUTUBE) return@withContext emptyList()
            if (source == HomeFeedSource.RADIO) {
                // Independent Radio Browser categories must not spend eight
                // serial network windows blocking Home. Three parallel queries
                // maximum avoids a burst of connections on mobile networks.
                val permits = Semaphore(3)
                val tasks = RadioBrowserMusic.categories.map { category ->
                    async(Dispatchers.IO) {
                        category to permits.withPermit {
                            safely {
                                cached("radio:${category.name}:${java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()}") {
                                    RadioBrowserMusic.browse(category)
                                }
                            }
                        }
                    }
                }
                val lanes = mutableListOf<HomeLane>()
                val seen = mutableSetOf<String>()
                for (task in tasks) {
                    val (category, tracks) = task.await()
                    val filtered = tracks.filter { seen.add(it.mediaId) }.take(14)
                    if (filtered.isNotEmpty()) {
                        val lane = HomeLane(source, HomeLaneKind.DISCOVER, filtered, category.titleRes)
                        lanes.add(lane)
                        onLane(lane)
                    }
                }
                return@withContext lanes
            }
            if (source == HomeFeedSource.FUNKWHALE) {
                val seen = mutableSetOf<String>()
                val lanes = mutableListOf<HomeLane>()
                for (kind in listOf(HomeLaneKind.NEW, HomeLaneKind.DISCOVER, HomeLaneKind.FOR_YOU)) {
                    val matches = safely {
                        cached("funkwhale:" + kind.name + ":" +
                            java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay() +
                            ":" + tasteArtists.take(1).joinToString("|")) {
                            FunkwhaleMusic.browse(kind, tasteArtists.firstOrNull())
                        }
                    }.filter { seen.add(it.mediaId) }
                        .sortedByDescending { it.playable }.take(14)
                    if (matches.isNotEmpty()) {
                        val lane = HomeLane(source, kind, matches)
                        lanes.add(lane)
                        onLane(lane)
                    }
                }
                return@withContext lanes
            }
            val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()
            val upstream = source.openverseId
            val themes = when (source) {
                HomeFeedSource.JAMENDO -> listOf("indie", "electronic", "jazz", "ambient")
                HomeFeedSource.WIKIMEDIA -> listOf("folk music", "classical", "traditional", "jazz")
                HomeFeedSource.FREESOUND -> listOf("instrumental", "melody", "ambient", "piano")
                HomeFeedSource.ARCHIVE -> listOf("ambient", "electronic", "jazz", "rock")
                HomeFeedSource.CCMIXTER -> listOf("remix", "instrumental", "downtempo", "electronica")
                HomeFeedSource.RADIO -> listOf("ambient", "jazz", "world", "electronic")
                else -> emptyList()
            }
            val theme = themes.takeIf { it.isNotEmpty() }
                ?.let { it[Math.floorMod(day, it.size.toLong()).toInt()] }
            val searchTaste = tasteArtists.firstOrNull { it.isNotBlank() }?.take(80)
            // Each lane has its own cache and failure boundary. If a source's
            // personalized search fails, its popular and new music still load.
            val queries = if (source == HomeFeedSource.RADIO) listOf(
                HomeLaneKind.TRENDING to { RadioBrowserMusic.browse(HomeLaneKind.TRENDING) },
                HomeLaneKind.DISCOVER to { RadioBrowserMusic.browse(HomeLaneKind.DISCOVER, theme) },
                HomeLaneKind.NEW to { RadioBrowserMusic.browse(HomeLaneKind.NEW) },
            ) else if (source == HomeFeedSource.CCMIXTER) listOf(
                HomeLaneKind.TRENDING to { CCMixterMusic.browse(HomeLaneKind.TRENDING) },
                HomeLaneKind.NEW to { CCMixterMusic.browse(HomeLaneKind.NEW) },
                HomeLaneKind.DISCOVER to { CCMixterMusic.browse(HomeLaneKind.DISCOVER, theme) },
            ) else if (source == HomeFeedSource.ARCHIVE) listOf(
                HomeLaneKind.TRENDING to { InternetArchiveMusic.browse(HomeLaneKind.TRENDING) },
                HomeLaneKind.NEW to { InternetArchiveMusic.browse(HomeLaneKind.NEW) },
                HomeLaneKind.DISCOVER to { InternetArchiveMusic.browse(HomeLaneKind.DISCOVER, theme) },
            ) else listOf(
                HomeLaneKind.TRENDING to { when (source) {
                    HomeFeedSource.WIKIMEDIA -> WikimediaMusic.browse("music")
                    HomeFeedSource.FREESOUND -> openverse("ambient", sourceFilter = upstream)
                    else -> openverse("music", sourceFilter = upstream, category = "music")
                } },
                HomeLaneKind.DISCOVER to { when (source) {
                    HomeFeedSource.WIKIMEDIA -> WikimediaMusic.browse(theme ?: "instrumental")
                    HomeFeedSource.FREESOUND -> openverse(theme ?: "instrumental", sourceFilter = upstream)
                    else -> openverse(theme ?: "instrumental", sourceFilter = upstream, category = "music")
                } },
            ) + if (searchTaste != null) listOf(
                HomeLaneKind.FOR_YOU to { when (source) {
                    HomeFeedSource.WIKIMEDIA -> WikimediaMusic.browse(searchTaste)
                    HomeFeedSource.FREESOUND -> openverse("instrumental", sourceFilter = upstream)
                    else -> openverse(searchTaste, sourceFilter = upstream, category = "music")
                } },
            ) else emptyList()
            val used = mutableSetOf<String>()
            var remainingStreamProbes = 7 // bounded across this entire source Home
            val lanes = mutableListOf<HomeLane>()
            for ((kind, loader) in queries) {
                val candidates = safely {
                    cached("homelane:${source.id}:${kind.name}:$day:${searchTaste.orEmpty().lowercase()}") { loader() }
                }
                // Keep licensed non-streamable records discoverable by a link to
                // their original page; playback itself remains HTTPS-only.
                val ordered = candidates.asSequence()
                    .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                    .filter { it.durationSeconds in 0..2400 }
                    .filter { it.audioCategory.isNullOrBlank() ||
                        it.audioCategory.equals("music", ignoreCase = true) }
                    .filter { t ->
                        val name = t.title.lowercase()
                        listOf("sound effect", "test tone", "pronunciation", "ringtone",
                            "alarm clock").none(name::contains)
                    }
                    .distinctBy { it.mediaId }
                    .sortedWith(compareByDescending<Track> { it.playable }
                        )
                    .filter { used.add(it.mediaId) }
                    .take(16).toList()
                    .map { track ->
                        if (track.streamCandidates.isNotEmpty() && remainingStreamProbes-- > 0) {
                            track.copy(streamUrl = track.streamCandidates
                                .firstOrNull { verifyOpenverseAudio(it) })
                        } else track
                    }
                    // Keep licensed, curated index-only cards as ORIGINAL RELEASE
                    // links. They are not playable, but their source Home must
                    // remain discoverable when a CDN requires login/blocks Range.
                    .sortedByDescending { it.playable }
                if (ordered.isNotEmpty()) {
                    val lane = HomeLane(source, kind, ordered)
                    lanes.add(lane)
                    // A slow or failing second Openverse query must not hold
                    // back results already returned by the first one.
                    onLane(lane)
                }
            }
            lanes
        }

    suspend fun artistTracks(source: Source, artist: String, id: String?): List<Track> =
        withContext(Dispatchers.IO) {
            cached("artist:${source.name}:${id.orEmpty()}:${artist.lowercase()}") {
                when (source) {
                    Source.OPENVERSE -> if (id == "wikimedia_audio") {
                        WikimediaMusic.artist(artist).ifEmpty {
                            // Commons full-text search does not always index
                            // the uploader/performer field. Index fallback is
                            // for creator discovery only: unverified files are
                            // original links, never presumed playable.
                            validateSearchTracks(openverse(creator = artist, sourceFilter = id))
                        }
                    } else validateSearchTracks(openverse(
                        creator = artist,
                        sourceFilter = id?.takeIf { it.matches(Regex("[a-z_]{2,30}")) },
                    ))
                    Source.ARCHIVE -> InternetArchiveMusic.artist(artist)
                    Source.CCMIXTER -> CCMixterMusic.byArtist(id ?: artist)
                    Source.RADIO -> emptyList()
                    Source.FUNKWHALE -> FunkwhaleMusic.artist(id, artist)
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
        category: String? = null,
    ): List<Track> {
        val args = mutableListOf(
            "page_size=${if (sourceFilter == "freesound") 12 else 20}",
            "filter_dead=true",
        )
        if (!query.isNullOrBlank()) args.add("q=${enc(query.take(100))}")
        else if (!creator.isNullOrBlank()) args.add("creator=${enc(creator.take(100))}")
        else return emptyList()
        if (!sourceFilter.isNullOrBlank()) args.add("source=${enc(sourceFilter)}")
        if (category != null) args.add("category=${enc(category)}")
        val result = requestOpenverse("https://api.openverse.org/v1/audio/?"
            + args.joinToString("&"))
        val items = result.optJSONArray("results") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val obj = items.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return@mapNotNull null
            if (obj.optBoolean("mature", false)) return@mapNotNull null
            val provider = obj.optString("source").lowercase()
            // Freesound classifies even melodic loops as sound effects. Only
            // admit items with strong musical title/tag evidence; never treat
            // arbitrary effects as songs.
            val musicText = buildString {
                append(obj.optString("title"))
                val itemTags = obj.optJSONArray("tags")
                for (tagIndex in 0 until minOf(itemTags?.length() ?: 0, 12)) {
                    append(" ")
                    append(itemTags?.optJSONObject(tagIndex)?.optString("name").orEmpty())
                }
            }.lowercase()
            val freesoundMusic = provider == "freesound" &&
                listOf("music", "melody", "instrumental", "ambient", "piano",
                    "guitar", "rhythm", "synth", "song", "beat", "musical",
                    "chord", "loop").any(musicText::contains) &&
                listOf("footstep", "explosion", "gunshot", "applause", "door slam",
                    "alarm", "car engine", "bird call", "spoken",
                    "pronunciation").none(musicText::contains)
            if (provider == "freesound" && !freesoundMusic) return@mapNotNull null
            if (provider != "freesound" &&
                obj.optString("category") == "sound_effect") return@mapNotNull null
            val title = obj.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val artist = obj.optString("creator").ifBlank { "Unknown artist" }
            val licenseUrl = obj.optString("license_url").takeIf {
                it.startsWith("https://creativecommons.org/") ||
                    it.startsWith("http://creativecommons.org/")
            }
            val alternates = obj.optJSONArray("alt_files")
            val candidates = (listOf(obj.optString("url")) +
                (0 until (alternates?.length() ?: 0))
                    .mapNotNull { alternates?.optJSONObject(it)?.optString("url") })
                .filter { it.startsWith("https://") && it.length < 2048 }
                .distinct().take(3)
            // A public index does not establish playback permission by itself.
            // Respect original publisher restrictions and expose the license.
            val mayStream = licenseUrl != null && obj.optString("license").isNotBlank()
            val genres = obj.optJSONArray("genres")
            val genre = (0 until (genres?.length() ?: 0))
                .mapNotNull { genres?.optString(it)?.takeIf(String::isNotBlank) }
                .joinToString(", ").ifBlank { null }
            val tags = obj.optJSONArray("tags")
            val tagGenre = (0 until (tags?.length() ?: 0))
                .mapNotNull { tags?.optJSONObject(it)?.optString("name") }
                .take(8).joinToString(", ")
            // Jamendo's audio_set URL is album artwork; other providers
            // use the official compressed Openverse thumbnail.
            val albumArtwork = obj.optJSONObject("audio_set")?.optString("url")
                ?.takeIf { provider == "jamendo" && it.startsWith("https://usercontent.jamendo.com") }
            val cover = albumArtwork ?: obj.optString("thumbnail")
                .takeIf { it.startsWith("https://") }
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
                streamUrl = null, // only set after a validated HTTPS audio Range response
                streamCandidates = if (mayStream) candidates else emptyList(),
                sourcePage = original,
                durationSeconds = (millis / 1000L).coerceIn(0, 86400).toInt(),
                license = licenseUrl,
                genre = genre ?: tagGenre.ifBlank { null },
                artistId = provider.takeIf(String::isNotBlank),
                artistPage = creatorPage,
                catalogProvider = provider.takeIf(String::isNotBlank),
                audioCategory = if (freesoundMusic) "music"
                    else obj.optString("category").ifBlank { null },
            )
        }.distinctBy { it.id }
    }

    private data class StreamProbe(val good: Boolean, val testedAt: Long)
    private val streamChecks = ConcurrentHashMap<String, StreamProbe>()

    /**
     * Openverse returns indexed media addresses, not a guarantee that the file
     * still exists. Validate a tiny HTTP Range request before offering Play.
     * Never follow a redirect off HTTPS or off the known catalog CDNs.
     */
    private fun verifyOpenverseAudio(address: String): Boolean {
        val now = System.currentTimeMillis()
        streamChecks[address]?.takeIf { now - it.testedAt < 15 * 60_000L }
            ?.let { return it.good }
        val verified = try { audioProbe(address) } catch (e: Exception) {
            Timber.tag("CapsuleOpenverse").d(e, "Indexed audio unavailable")
            false
        }
        if (streamChecks.size > 256) streamChecks.clear()
        streamChecks[address] = StreamProbe(verified, now)
        return verified
    }

    private fun audioProbe(address: String, hops: Int = 0): Boolean {
        if (hops > 3) return false
        val parsed = URL(address)
        if (parsed.protocol != "https") return false
        val host = parsed.host.lowercase()
        if (listOf("jamendo.com", "wikimedia.org", "freesound.org")
                .none { host == it || host.endsWith("." + it) }) return false
        val proxy = YouTube.streamProxy
        val conn = (if (proxy != null) parsed.openConnection(proxy)
            else parsed.openConnection()) as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 2800
        conn.readTimeout = 2800
        conn.setRequestProperty("Range", "bytes=0-31")
        conn.setRequestProperty("Accept", "audio/*")
        conn.setRequestProperty("User-Agent", "CapsuleMusic/1.0")
        try {
            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val destination = conn.getHeaderField("Location") ?: return false
                val next = URL(parsed, destination)
                return audioProbe(next.toString(), hops + 1)
            }
            if (status != 200 && status != 206) return false
            val contentType = conn.contentType.orEmpty().lowercase()
            // Repositories/CDNs sometimes emit application/octet-stream for MP3.
            if (contentType.contains("text/") || contentType.contains("json") ||
                contentType.contains("html") || contentType.contains("image/")) return false
            val head = ByteArray(32)
            val count = conn.inputStream.use { it.read(head) }
            if (count <= 0) return false
            val signature = String(head, 0, count.coerceAtMost(8), Charsets.US_ASCII)
            return contentType.startsWith("audio/") ||
                signature.startsWith("ID3") || signature.startsWith("OggS") ||
                signature.startsWith("fLaC") ||
                (count >= 2 && head[0].toInt() and 0xff == 0xff &&
                    head[1].toInt() and 0xe0 == 0xe0)
        } finally { conn.disconnect() }
    }

    private fun validateSearchTracks(tracks: List<Track>): List<Track> {
        var quota = 5
        return tracks.map { track ->
            if (track.streamCandidates.isEmpty() || quota-- <= 0) track
            else track.copy(streamUrl = track.streamCandidates.firstOrNull {
                verifyOpenverseAudio(it)
            })
        }
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
        // Respect Capsule's configured network proxy for independent catalogs.
        val networkProxy = YouTube.streamProxy
        // No explicit proxy means use the Android/system default connection.
        // Proxy.NO_PROXY incorrectly ignored legitimate system HTTP proxies.
        val connection = (if (networkProxy != null) URL(url).openConnection(networkProxy)
            else URL(url).openConnection()) as HttpURLConnection
        connection.apply {
            connectTimeout = 4500
            readTimeout = 6500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CapsuleMusic/experimental-discovery")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                val provider = if (url.startsWith("https://api.openverse.org/"))
                    "Openverse" else "Catalog"
                throw java.io.IOException("$provider HTTP ${connection.responseCode}")
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
