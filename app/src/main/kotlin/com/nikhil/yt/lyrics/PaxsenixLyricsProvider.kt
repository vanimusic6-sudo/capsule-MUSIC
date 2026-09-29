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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        context.dataStore[EnablePaxsenixAppleMusicKey] ?: false

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
        for (track in candidates.take(MAX_TRACKS_TRIED)) {
            fetchRelayLyrics(track.id)?.let { return it }
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

        for (query in queries) {
            val results = runSearch(query)
            if (results.isNotEmpty()) {
                return rank(results, cleanTitle, cleanArtist, duration)
            }
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

    private suspend fun fetchRelayLyrics(trackId: String): String? {
        val response =
            runCatchingCancellable {
                client.get("$RELAY/apple-music/lyrics") {
                    parameter("id", trackId)
                }
            }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val body =
            runCatchingCancellable { response.body<PaxsenixLyrics>() }
                .getOrNull()
                ?: return null

        return body.ttmlContent?.takeIf(String::isNotBlank)
            ?: body.elrcMultiPerson?.takeIf(String::isNotBlank)
            ?: body.elrc?.takeIf(String::isNotBlank)
            ?: body.plain?.takeIf(String::isNotBlank)
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

    private const val MAX_TRACKS_TRIED = 5
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
