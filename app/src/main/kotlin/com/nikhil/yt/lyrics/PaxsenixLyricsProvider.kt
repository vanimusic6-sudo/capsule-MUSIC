/**
 * Capsule MUSIC
 * Paxsenix: Apple Music's lyrics, reached through a public relay.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.runCatchingCancellable
import android.content.Context
import com.nikhil.yt.constants.EnablePaxsenixKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import kotlin.math.abs

@Serializable
private data class PaxsenixLyrics(
    val elrcMultiPerson: String? = null,
    val elrc: String? = null,
    val ttmlContent: String? = null,
    val plain: String? = null,
)

@Serializable
private data class AppleArtwork(val url: String? = null)

@Serializable
private data class AppleAttributes(
    val name: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AppleArtwork? = null,
)

@Serializable
private data class AppleSongDetail(val attributes: AppleAttributes)

@Serializable
private data class AppleResources(val songs: Map<String, AppleSongDetail>? = null)

@Serializable
private data class AppleSongRef(val id: String)

@Serializable
private data class AppleSongs(val data: List<AppleSongRef> = emptyList())

@Serializable
private data class AppleResults(val songs: AppleSongs? = null)

@Serializable
private data class AppleSearchResponse(
    val results: AppleResults,
    val resources: AppleResources? = null,
)

private data class AppleTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
)

@Serializable
internal data class PaxsenixProviderStats(
    val hits: Int = 0,
    val errors: Int = 0,
    val success_rate: String = "0%",
)

@Serializable
internal data class PaxsenixRequestLogEntry(
    val timestamp: String = "",
    val endpoint: String = "",
    val provider: String = "",
    val success: Boolean = false,
    val response_time_ms: Double = 0.0,
)

@Serializable
internal data class PaxsenixStats(
    val uptime_seconds: Double = 0.0,
    val total_requests: Int = 0,
    val successful_requests: Int = 0,
    val failed_requests: Int = 0,
    val overall_success_rate: String = "0%",
    val providers: Map<String, PaxsenixProviderStats> = emptyMap(),
    val request_log: List<PaxsenixRequestLogEntry> = emptyList(),
)

/**
 * Apple Music's catalogue has the lyrics; paxsenix.org relays them.
 *
 * The catalogue search needs a bearer token, and the only one available to anything that is not
 * Apple's own player is the anonymous one their web player mints for itself — so it is read out of
 * the web player's script bundle, cached, and re-read when it expires. This is an undocumented
 * endpoint used the way the rest of the ecosystem uses it, not a credential belonging to anybody:
 * no account is involved and nothing is decrypted. It is off by default all the same.
 */
object PaxsenixLyricsProvider : LyricsProvider {
    override val name = "Paxsenix"

    private const val RELAY = "https://lyrics.paxsenix.org"
    private const val APPLE_CATALOG = "https://amp-api.music.apple.com/v1/catalog/us"
    private const val APPLE_WEB = "https://beta.music.apple.com"

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnablePaxsenixKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        if (title.isBlank()) {
            return Result.failure(NoLyricsFromProvider("Paxsenix needs a title"))
        }

        val backends: List<Pair<String, suspend () -> String?>> =
            listOf(
                "Apple Music" to { fetchAppleMusic(title, artist, album, duration) },
                "NetEase" to { fetchNetEase(title, artist, duration) },
                "Spotify" to { fetchSpotify(title, artist, duration) },
                "Musixmatch" to { fetchPaxsenixMusixmatch(title, artist, duration) },
            )

        for ((backend, fetch) in backends) {
            val lyrics =
                runCatchingCancellable { fetch() }
                    .onFailure { Timber.tag(name).d(it, "%s backend failed", backend) }
                    .getOrNull()
            if (!lyrics.isNullOrBlank()) return Result.success(lyrics)
        }

        return Result.failure(NoLyricsFromProvider("No Paxsenix backend returned lyrics"))
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        if (title.isBlank()) return

        val seen = LinkedHashSet<String>()
        val backends: List<suspend () -> String?> =
            listOf(
                { fetchAppleMusic(title, artist, album, duration) },
                { fetchNetEase(title, artist, duration) },
                { fetchSpotify(title, artist, duration) },
                { fetchPaxsenixMusixmatch(title, artist, duration) },
            )

        for (fetch in backends) {
            val lyrics = runCatchingCancellable { fetch() }.getOrNull()?.trim().orEmpty()
            if (lyrics.isNotEmpty() && seen.add(lyrics)) callback(lyrics)
        }
    }

    internal suspend fun fetchAppleMusic(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): String? {
        val candidates = search(title, artist, album, duration)
        for (track in candidates.take(MAX_TRACKS_TRIED)) {
            fetchLyrics(track.id)?.let { return it }
        }
        return null
    }

    private suspend fun search(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): List<AppleTrack> {
        val cleanTitle = LyricsQueryCleanup.title(title)
        val cleanArtist = LyricsQueryCleanup.artist(artist)
        val queries =
            listOfNotNull(
                "$cleanTitle $cleanArtist".trim(),
                cleanTitle.takeIf { it.isNotBlank() },
                album?.takeIf { it.isNotBlank() }?.let { "$cleanTitle $cleanArtist $it" },
            ).distinct()

        for (query in queries) {
            val results = runSearch(query)
            if (results.isNotEmpty()) return rank(results, cleanTitle, cleanArtist, duration)
        }
        return emptyList()
    }

    private suspend fun runSearch(query: String): List<AppleTrack> {
        val token = appleToken() ?: return emptyList()
        val response =
            runCatchingCancellable {
                client.get("$APPLE_CATALOG/search") {
                    parameter("term", query)
                    parameter("types", "songs")
                    parameter("limit", "25")
                    parameter("l", "en-US")
                    parameter("platform", "web")
                    parameter("format[resources]", "map")
                    header("Authorization", "Bearer $token")
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                }
            }.getOrNull() ?: return emptyList()

        if (response.status.value == 401) {
            // The token expired mid-flight; drop it so the next attempt mints a fresh one.
            cachedToken = null
            return emptyList()
        }
        if (!response.status.isSuccess()) return emptyList()

        val body = runCatchingCancellable { response.body<AppleSearchResponse>() }.getOrNull() ?: return emptyList()
        val songs = body.results.songs?.data ?: return emptyList()
        return songs.mapNotNull { ref ->
            val attributes = body.resources?.songs?.get(ref.id)?.attributes ?: return@mapNotNull null
            AppleTrack(
                id = ref.id,
                title = attributes.name,
                artist = attributes.artistName,
                durationMs = attributes.durationInMillis,
            )
        }
    }

    /**
     * Sorts the catalogue's answers by how likely each is to be the same recording.
     *
     * Duration carries the most weight because a title and artist match tells you nothing about
     * which of six versions you got, and "Mixed" or "Remix" in a result that was not asked for is
     * the usual way a wrong one scores well on everything else.
     */
    private fun rank(
        results: List<AppleTrack>,
        cleanTitle: String,
        cleanArtist: String,
        duration: Int,
    ): List<AppleTrack> {
        val wantedMs = duration * 1_000L
        val wantsRemix = cleanTitle.contains("remix", ignoreCase = true)
        val wantsMixed = cleanTitle.contains("mixed", ignoreCase = true)

        return results
            .map { track ->
                var score = 0.0
                track.durationMs?.let { found ->
                    val gap = abs(found - wantedMs)
                    score +=
                        when {
                            wantedMs <= 0L -> 0.0
                            gap <= 2_000 -> 100.0
                            gap <= 5_000 -> 50.0
                            gap <= 10_000 -> 10.0
                            else -> -50.0
                        }
                }

                val foundTitle = LyricsQueryCleanup.title(track.title).lowercase()
                val wantedTitle = cleanTitle.lowercase()
                when {
                    foundTitle == wantedTitle -> score += 80
                    foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle) -> score += 40
                }

                if (track.title.contains("mixed", ignoreCase = true) && !wantsMixed) score -= 60
                if (track.title.contains("remix", ignoreCase = true) && !wantsRemix) score -= 40

                val foundArtist = track.artist.lowercase()
                if (cleanArtist.isNotBlank() && foundArtist.contains(cleanArtist.lowercase())) {
                    score += 50
                }

                track to score
            }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Whatever the relay has, in the order our own renderer can do most with.
     *
     * TTML first because that is the one shape this app turns into per-word timing and multiple
     * voices; the ELRC variants are already LRC and keep line timing; plain text is the floor.
     */
    private suspend fun fetchLyrics(trackId: String): String? {
        val response =
            runCatchingCancellable {
                client.get("$RELAY/apple-music/lyrics") { parameter("id", trackId) }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val body = runCatchingCancellable { response.body<PaxsenixLyrics>() }.getOrNull() ?: return null
        return body.ttmlContent?.takeIf { it.isNotBlank() }
            ?: body.elrcMultiPerson?.takeIf { it.isNotBlank() }
            ?: body.elrc?.takeIf { it.isNotBlank() }
            ?: body.plain?.takeIf { it.isNotBlank() }
    }


    private data class RelayTrack(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    internal suspend fun fetchSpotify(
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val track =
            searchRelayTrack(
                path = "spotify/search",
                title = title,
                artist = artist,
                durationMs = duration.coerceAtLeast(0) * 1_000L,
            ) ?: return null

        val response =
            runCatchingCancellable {
                client.get("$RELAY/spotify/lyrics") {
                    parameter("id", track.id)
                }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return parseRelayLyrics(response.bodyAsText())
    }

    internal suspend fun fetchNetEase(
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val track =
            searchRelayTrack(
                path = "netease/search",
                title = title,
                artist = artist,
                durationMs = duration.coerceAtLeast(0) * 1_000L,
            ) ?: return null

        val response =
            runCatchingCancellable {
                client.get("$RELAY/netease/lyrics") {
                    parameter("id", track.id)
                    parameter("word", "true")
                }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return parseRelayLyrics(response.bodyAsText())
    }

    internal suspend fun fetchPaxsenixMusixmatch(
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val query = "$title $artist".trim()
        for (type in listOf("word", null)) {
            val response =
                runCatchingCancellable {
                    client.get("$RELAY/musixmatch/lyrics") {
                        parameter("q", query)
                        parameter("t", title)
                        parameter("a", artist)
                        if (duration > 0) parameter("d", duration)
                        if (type != null) parameter("type", type)
                    }
                }.getOrNull() ?: continue
            if (!response.status.isSuccess()) continue
            parseRelayLyrics(response.bodyAsText())?.let { return it }
        }
        return null
    }

    internal suspend fun fetchYouTube(
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val track =
            searchRelayTrack(
                path = "youtube/search",
                title = title,
                artist = artist,
                durationMs = duration.coerceAtLeast(0) * 1_000L,
            ) ?: return null

        val response =
            runCatchingCancellable {
                client.get("$RELAY/youtube/lyrics") {
                    parameter("id", track.id)
                }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return parseRelayLyrics(response.bodyAsText())
    }

    internal suspend fun getStats(): Result<PaxsenixStats> =
        runCatchingCancellable {
            val response = client.get("$RELAY/api/stats")
            if (!response.status.isSuccess()) {
                error("Paxsenix stats HTTP ${response.status.value}")
            }
            response.body<PaxsenixStats>()
        }

    private suspend fun searchRelayTrack(
        path: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): RelayTrack? {
        val response =
            runCatchingCancellable {
                client.get("$RELAY/$path") {
                    parameter("q", "$title $artist".trim())
                }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val root =
            runCatchingCancellable {
                json.parseToJsonElement(response.bodyAsText())
            }.getOrNull() ?: return null

        val candidates = buildList { root.collectRelayTracks(this) }
        return candidates
            .map { it to relayScore(it, title, artist, durationMs) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun JsonElement.collectRelayTracks(destination: MutableList<RelayTrack>) {
        when (this) {
            is JsonArray -> forEach { it.collectRelayTracks(destination) }
            is JsonObject -> {
                toRelayTrack()?.let(destination::add)
                values.forEach { it.collectRelayTracks(destination) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.toRelayTrack(): RelayTrack? {
        val details = (this["attributes"] as? JsonObject) ?: this
        val id =
            firstString("realId", "id", "trackId", "track_id")
                ?: details.firstString("realId", "id", "trackId", "track_id")
                ?: return null
        val title = details.firstString("name", "title", "trackName", "track_name") ?: return null
        val artist =
            details.firstString("artistName", "artist_name")
                ?: details.artistNames().orEmpty()
        val duration =
            details.firstLong("durationInMillis", "durationMs", "duration_ms", "duration", "dt")
                .toDurationMs()
        return RelayTrack(id, title, artist, duration)
    }

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys
            .asSequence()
            .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)

    private fun JsonObject.firstLong(vararg keys: String): Long? =
        keys
            .asSequence()
            .mapNotNull { key -> (this[key] as? JsonPrimitive)?.longOrNull }
            .firstOrNull()

    private fun JsonObject.artistNames(): String? {
        val artists = this["artists"] ?: this["ar"] ?: this["artist"] ?: return null
        return when (artists) {
            is JsonPrimitive -> artists.contentOrNull
            is JsonObject -> artists.firstString("name", "artistName", "title")
            is JsonArray ->
                artists
                    .mapNotNull { value ->
                        when (value) {
                            is JsonPrimitive -> value.contentOrNull
                            is JsonObject -> value.firstString("name", "artistName", "title")
                            else -> null
                        }
                    }.joinToString(", ")
                    .takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun Long?.toDurationMs(): Long =
        when {
            this == null || this <= 0L -> 0L
            this < 10_000L -> this * 1_000L
            else -> this
        }

    private fun relayScore(
        track: RelayTrack,
        title: String,
        artist: String,
        durationMs: Long,
    ): Int {
        val wantedTitle = LyricsQueryCleanup.title(title)
        val wantedArtist = LyricsQueryCleanup.artist(artist)
        var score = 0

        score +=
            when {
                track.title.equals(wantedTitle, ignoreCase = true) -> 40
                track.title.contains(wantedTitle, ignoreCase = true) ||
                    wantedTitle.contains(track.title, ignoreCase = true) -> 20
                else -> 0
            }
        if (wantedArtist.isNotBlank()) {
            score +=
                when {
                    track.artist.equals(wantedArtist, ignoreCase = true) -> 30
                    track.artist.contains(wantedArtist, ignoreCase = true) ||
                        wantedArtist.contains(track.artist, ignoreCase = true) -> 12
                    else -> 0
                }
        }
        if (durationMs > 0L && track.durationMs > 0L) {
            val gap = abs(durationMs - track.durationMs)
            score +=
                when {
                    gap <= 2_500L -> 30
                    gap <= 6_000L -> 16
                    gap <= 10_000L -> 6
                    else -> -25
                }
        }
        return score
    }

    private val relayLyricsKeys =
        listOf("lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lyric")

    private fun parseRelayLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("<tt") || trimmed.startsWith("<?xml")) return trimmed

        val payload = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return extractRelayLyrics(payload)
    }

    private fun extractRelayLyrics(element: JsonElement): String? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive ->
                if (element.isString) {
                    element.content.trim().takeIf(String::isNotEmpty)?.let { value ->
                        val nested = runCatching { json.parseToJsonElement(value) }.getOrNull()
                        if (nested != null && nested !is JsonPrimitive) extractRelayLyrics(nested) else value
                    }
                } else {
                    null
                }

            is JsonArray ->
                relayLinesToLrc(element)
                    ?: element.mapNotNull(::extractRelayLyrics).joinToString("\n").trim().takeIf(String::isNotEmpty)

            is JsonObject -> {
                (element["klyric"] as? JsonObject)
                    ?.get("lyric")
                    ?.let(::extractRelayLyrics)
                    ?: (element["lrc"] as? JsonObject)
                        ?.get("lyric")
                        ?.let(::extractRelayLyrics)
                    ?: relayLyricsKeys
                        .asSequence()
                        .mapNotNull { key -> element[key]?.let(::extractRelayLyrics) }
                        .firstOrNull()
                    ?: element["metadata"]?.let(::extractRelayLyrics)
                    ?: element["words"]?.let(::extractRelayLyrics)
            }
        }

    private fun relayLinesToLrc(lines: JsonArray): String? {
        val converted =
            lines.mapNotNull { value ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val text =
                    obj.firstString("words", "text", "line", "lyric")
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null

                val timeTag = obj.firstString("timeTag", "time_tag")
                // Fields explicitly named *Ms are already milliseconds. Only the generic
                // "time" field needs the seconds-vs-ms compatibility heuristic.
                val millis =
                    obj.firstLong("startTimeMs", "start_time_ms", "timestamp")
                        ?: obj.firstLong("time")?.toDurationMs()

                when {
                    !timeTag.isNullOrBlank() -> "[" + timeTag.removePrefix("[").removeSuffix("]") + "]" + text
                    millis != null && millis > 0L -> lrcStamp(millis) + text
                    else -> text
                }
            }
        if (converted.isEmpty()) return null
        return converted.joinToString("\n")
    }

    private fun lrcStamp(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1_000L) % 60L
        val hundredths = (safe % 1_000L) / 10L
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    private suspend fun appleToken(): String? {
        cachedToken?.let { return it }
        return tokenMutex.withLock {
            cachedToken?.let { return@withLock it }
            val token =
                runCatchingCancellable {
                    val index = client.get(APPLE_WEB).bodyAsText()
                    val bundle =
                        INDEX_JS_REGEX.find(index)?.value
                            ?: error("Apple web player bundle not found")
                    val script = client.get("$APPLE_WEB$bundle").bodyAsText()
                    JWT_REGEX.find(script)?.value ?: error("Apple web player token not found")
                }.onFailure { Timber.tag(name).d(it, "Could not read the Apple Music token") }
                    .getOrNull()
            cachedToken = token
            token
        }
    }

    private const val MAX_TRACKS_TRIED = 5
    private val INDEX_JS_REGEX = Regex("""/assets/index~[^/"']+\.js""")
    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""")
}

/**
 * Trimming a YouTube title down to something a music catalogue will recognise.
 *
 * Shared because every provider that searches by name needs the same thing, and each one growing
 * its own slightly different list of patterns is how they end up disagreeing about which song they
 * were asked for.
 */
internal object LyricsQueryCleanup {
    private val noise =
        listOf(
            Regex("""\s*\(.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\[.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?]""", RegexOption.IGNORE_CASE),
            Regex("""\s*【.*?】"""),
            Regex("""\s*\|.*$"""),
            Regex("""\s*-\s*(official|video|audio|lyrics?|visualizer).*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*\((feat\.|ft\.).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*(feat\.|ft\.).*$""", RegexOption.IGNORE_CASE),
        )

    private val artistSeparators =
        listOf(" & ", " and ", ", ", " x ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    fun title(raw: String): String {
        var cleaned = raw.trim()
        noise.forEach { cleaned = cleaned.replace(it, "") }
        return cleaned.trim()
    }

    /** Catalogues index a track under its lead artist, so everyone after the first is noise. */
    fun artist(raw: String): String {
        var cleaned = raw.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2).first()
                break
            }
        }
        return cleaned.trim()
    }
}
