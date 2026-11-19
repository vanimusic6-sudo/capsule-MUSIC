/**
 * Capsule MUSIC
 * Paxsenix Apple Music lyrics relay.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnablePaxsenixAppleMusicKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.runCatchingCancellable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import kotlin.math.abs
import java.util.Locale

@Serializable
internal data class PaxsenixWord(
    val text: String = "",
    val timestamp: Long? = null,
    val endtime: Long? = null,
)

@Serializable
internal data class PaxsenixLine(
    val timestamp: Long = 0L,
    val text: List<PaxsenixWord> = emptyList(),
    val background: Boolean = false,
)

/** The relay also sends structured Apple lines without any TTML/ELRC fields. */
@Serializable
internal data class PaxsenixLyrics(
    val type: String? = null,
    val content: List<PaxsenixLine> = emptyList(),
    val elrcMultiPerson: String? = null,
    val elrc: String? = null,
    val ttmlContent: String? = null,
    val plain: String? = null,
)

internal fun paxsenixLyricsText(payload: PaxsenixLyrics): String? {
    payload.ttmlContent?.takeIf(String::isNotBlank)?.let { return it }
    payload.elrcMultiPerson?.takeIf(String::isNotBlank)?.let { return it }
    payload.elrc?.takeIf(String::isNotBlank)?.let { return it }
    val lines = payload.content.mapNotNull { line ->
        val words = line.text.filter { it.text.isNotBlank() }
        if (words.isEmpty()) return@mapNotNull null
        val joined = if (words.any { it.text.any(Char::isWhitespace) }) {
            words.joinToString("") { it.text }
        } else words.joinToString(" ") { it.text }
        val time = paxsenixTimestamp(line.timestamp)
        if (payload.type.equals("Syllable", ignoreCase = true) &&
            words.all { it.timestamp != null }) {
            buildString {
                append("[").append(time).append("]")
                words.forEach { word ->
                    append("<").append(paxsenixTimestamp(word.timestamp!!)).append(">")
                    append(word.text)
                }
                words.last().endtime?.let { append("<").append(paxsenixTimestamp(it)).append(">") }
            }
        } else "[$time]$joined"
    }
    if (lines.isNotEmpty()) return lines.joinToString("\n")
    return payload.plain?.takeIf(String::isNotBlank)
}

private fun paxsenixTimestamp(millis: Long): String {
    val ms = millis.coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d.%02d",
        ms / 60_000, (ms / 1_000) % 60, (ms % 1_000) / 10)
}

@Serializable
private data class AppleAttributes(
    val name: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val durationInMillis: Long? = null,
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
private data class ITunesTrack(
    val trackId: Long? = null,
    val trackName: String = "",
    val artistName: String = "",
    val trackTimeMillis: Long? = null,
)

@Serializable
private data class ITunesSearchResponse(
    val results: List<ITunesTrack> = emptyList(),
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
 * The only Paxsenix backend Capsule exposes.
 *
 * The availability data showed the other relay backends either not returning usable lyrics in
 * practice or being too unreliable for a production fallback. Apple Music remains because it
 * consistently returns real TTML/ELRC/plain lyrics, and its catalogue matching is done locally so
 * Capsule can reject the wrong recording before asking the relay for text.
 */
object PaxsenixLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Apple Music"

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
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnablePaxsenixAppleMusicKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatchingCancellable {
            if (title.isBlank()) {
                throw NoLyricsFromProvider("Paxsenix Apple Music needs a title")
            }

            fetchAppleMusic(title, artist, album, duration)
                ?.takeIf(String::isNotBlank)
                ?: throw NoLyricsFromProvider("Paxsenix Apple Music returned no lyrics")
        }

    internal suspend fun fetchAppleMusic(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): String? {
        val candidates = search(title, artist, album, duration)
        val attempted = mutableSetOf<String>()
        val primaryIds = candidates.take(MAX_TRACKS_TRIED).map(AppleTrack::id)
        attempted.addAll(primaryIds)
        fetchFirstRelayLyrics(primaryIds)?.let { return it }
        // Public iTunes IDs do not always line up with the relay's catalog
        // edition (explicit/clean, different regions). Previously any iTunes
        // matches prevented querying the Apple catalog even if relay lyrics
        // were missing for every one. Only pay for fallback on an actual miss.
        val cleanTitle = LyricsQueryCleanup.title(title)
        val cleanArtist = LyricsQueryCleanup.artist(artist)
        val queries = listOf("$cleanTitle $cleanArtist".trim(), cleanTitle)
            .filter(String::isNotBlank).distinct()
        for (query in queries) {
            val candidatesFromApple = rank(
                runSearch(query), cleanTitle, cleanArtist, duration,
            )
            val alternativeIds = candidatesFromApple.take(MAX_TRACKS_TRIED)
                .map(AppleTrack::id).filter(attempted::add)
            fetchFirstRelayLyrics(alternativeIds)?.let { return it }
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
                cleanTitle.takeIf(String::isNotBlank),
                album
                    ?.takeIf(String::isNotBlank)
                    ?.let { "$cleanTitle $cleanArtist $it" },
            ).distinct()

        // Public iTunes Search does not require an Apple Music web-player
        // bearer token. The older bundle-scraping path breaks whenever Apple's
        // JS name/token changes, leaving this provider silent for every track.
        for (query in queries) {
            val publicMatches = rank(
                runITunesSearch(query), cleanTitle, cleanArtist, duration,
            )
            if (publicMatches.isNotEmpty()) return publicMatches
        }

        // Keep the catalog endpoint as a fallback for regional/unusual songs.
        for (query in queries) {
            val privateMatches = rank(
                runSearch(query), cleanTitle, cleanArtist, duration,
            )
            if (privateMatches.isNotEmpty()) return privateMatches
        }
        return emptyList()
    }

    /** Resolve IDs without scraping a changing Apple Music JS bundle. */
    private suspend fun runITunesSearch(query: String): List<AppleTrack> =
        runCatchingCancellable {
            val response = client.get("https://itunes.apple.com/search") {
                parameter("term", query)
                parameter("media", "music")
                parameter("entity", "song")
                parameter("country", "US")
                parameter("limit", 25)
            }
            if (!response.status.isSuccess()) return@runCatchingCancellable emptyList()
            json.decodeFromString<ITunesSearchResponse>(response.bodyAsText())
                .results.mapNotNull { item ->
                    item.trackId?.let { trackId ->
                        AppleTrack(
                            id = trackId.toString(),
                            title = item.trackName,
                            artist = item.artistName,
                            durationMs = item.trackTimeMillis,
                        )
                    }
                }
        }.onFailure { Timber.tag(name).d(it, "iTunes search unavailable") }
            .getOrDefault(emptyList())

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
            cachedToken = null
            return emptyList()
        }
        if (!response.status.isSuccess()) return emptyList()

        val body =
            runCatchingCancellable { response.body<AppleSearchResponse>() }
                .getOrNull()
                ?: return emptyList()
        val songs = body.results.songs?.data ?: return emptyList()

        return songs.mapNotNull { ref ->
            val attributes =
                body.resources
                    ?.songs
                    ?.get(ref.id)
                    ?.attributes
                    ?: return@mapNotNull null

            AppleTrack(
                id = ref.id,
                title = attributes.name,
                artist = attributes.artistName,
                durationMs = attributes.durationInMillis,
            )
        }
    }

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
                            gap <= 2_000L -> 100.0
                            gap <= 5_000L -> 50.0
                            gap <= 10_000L -> 10.0
                            else -> -50.0
                        }
                }

                val foundTitle = LyricsQueryCleanup.title(track.title).lowercase()
                val wantedTitle = cleanTitle.lowercase()
                when {
                    foundTitle == wantedTitle -> score += 80.0
                    foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle) ->
                        score += 40.0
                }

                if (track.title.contains("mixed", ignoreCase = true) && !wantsMixed) {
                    score -= 60.0
                }
                if (track.title.contains("remix", ignoreCase = true) && !wantsRemix) {
                    score -= 40.0
                }

                if (
                    cleanArtist.isNotBlank() &&
                    track.artist.contains(cleanArtist, ignoreCase = true)
                ) {
                    score += 50.0
                }

                track to score
            }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { (_, score) -> score }
            .map { (track, _) -> track }
    }

    /** Hedge only the best 3 catalog matches and cancel unfinished requests on success. */
    private suspend fun fetchFirstRelayLyrics(ids: List<String>): String? = supervisorScope {
        val unique = ids.distinct()
        if (unique.isEmpty()) return@supervisorScope null
        val output = Channel<String?>(Channel.UNLIMITED)
        val jobs = unique.mapIndexed { index, id ->
            launch(Dispatchers.IO) {
                // Fast first ID sends no secondary HTTP request; backups start
                // only if the best match did not answer within a short window.
                delay(index * 350L)
                output.send(fetchRelayLyrics(id))
            }
        }
        try {
            repeat(jobs.size) {
                output.receive()?.let { return@supervisorScope it }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            output.close()
        }
    }

    private suspend fun fetchRelayLyrics(trackId: String): String? {
        val response =
            runCatchingCancellable {
                client.get("$RELAY/apple-music/lyrics") {
                    parameter("id", trackId)
                }
            }.getOrNull() ?: return null

        if (!response.status.isSuccess()) {
            // An HTTP 401/403 is not a malformed lyric; the relay may now
            // require authentication. Keep the other sources available.
            Timber.tag(name).d("Paxsenix relay HTTP %d", response.status.value)
            return null
        }
        val raw = runCatchingCancellable { response.bodyAsText() }.getOrNull() ?: return null
        return parseRelayLyrics(raw).also { parsed ->
            if (parsed == null) Timber.tag(name).d("Paxsenix returned no usable lyric payload")
        }
    }

    /** Public relays have returned Apple line JSON, TTML strings and wrapped results.
     *  Reject explicit API errors rather than treating their message as song text. */
    internal fun parseRelayLyrics(raw: String): String? {
        val body = raw.trim()
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return body.takeIf { it.startsWith("<tt", true) ||
                it.contains(Regex("""\\[[0-9]{1,3}:[0-9]{2}""")) }
        return extractRelayLyrics(root, 0)
    }

    private fun extractRelayLyrics(element: JsonElement, depth: Int): String? {
        if (depth > 5 || element == JsonNull) return null
        return when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return null
                val text = element.content.trim()
                if (text.isBlank()) return null
                val decoded = runCatching { json.parseToJsonElement(text) }.getOrNull()
                if (decoded != null && decoded !is JsonPrimitive) extractRelayLyrics(decoded, depth + 1)
                else text.takeIf { it.isNotBlank() }
            }
            is JsonArray -> element.mapNotNull { extractRelayLyrics(it, depth + 1) }
                .joinToString("\\n").takeIf(String::isNotBlank)
            is JsonObject -> {
                val isError = (element["success"] as? JsonPrimitive)?.booleanOrNull == false ||
                    (element["ok"] as? JsonPrimitive)?.booleanOrNull == false ||
                    (element["error"] != null && element["error"] != JsonNull &&
                        element["error"] != JsonPrimitive(false)) ||
                    (element["type"] as? JsonPrimitive)?.contentOrNull == "error"
                if (isError) return null

                runCatching { json.decodeFromJsonElement<PaxsenixLyrics>(element) }.getOrNull()
                    ?.let(::paxsenixLyricsText)
                    ?.takeIf(String::isNotBlank)
                    ?: sequenceOf("ttmlContent", "elrcMultiPerson", "elrc",
                        "syncedLyrics", "lrc", "plain", "plainLyrics",
                        "lyrics", "content", "data", "result", "text")
                        .mapNotNull { key -> element[key]?.let { extractRelayLyrics(it, depth + 1) } }
                        .firstOrNull(String::isNotBlank)
            }
            else -> null
        }
    }

    internal suspend fun getStats(): Result<PaxsenixStats> =
        runCatchingCancellable {
            val response = client.get("$RELAY/api/stats")
            if (!response.status.isSuccess()) {
                error("Paxsenix stats HTTP ${response.status.value}")
            }
            response.body<PaxsenixStats>()
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
                    JWT_REGEX.find(script)?.value
                        ?: error("Apple web player token not found")
                }
                    .onFailure {
                        Timber.tag(name).d(it, "Could not read the Apple Music token")
                    }
                    .getOrNull()

            cachedToken = token
            token
        }
    }

    private const val MAX_TRACKS_TRIED = 3
    private val INDEX_JS_REGEX = Regex("""/assets/index~[^/"']+\.js""")
    private val JWT_REGEX =
        Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""")
}

/**
 * Trimming a YouTube title down to something a music catalogue will recognise.
 */
internal object LyricsQueryCleanup {
    private val noise =
        listOf(
            Regex(
                """\s*\(.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """\s*\[.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?]""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*【.*?】"""),
            Regex("""\s*\|.*$"""),
            Regex(
                """\s*-\s*(official|video|audio|lyrics?|visualizer).*$""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*\((feat\.|ft\.).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*(feat\.|ft\.).*$""", RegexOption.IGNORE_CASE),
        )

    private val artistSeparators =
        listOf(
            " & ",
            " and ",
            ", ",
            " x ",
            " feat. ",
            " feat ",
            " ft. ",
            " ft ",
            " featuring ",
            " with ",
        )

    fun title(raw: String): String {
        var cleaned = raw.trim()
        noise.forEach { cleaned = cleaned.replace(it, "") }
        return cleaned.trim()
    }

    fun artist(raw: String): String {
        var cleaned = raw.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned =
                    cleaned
                        .split(separator, ignoreCase = true, limit = 2)
                        .first()
                break
            }
        }
        return cleaned.trim()
    }
}
