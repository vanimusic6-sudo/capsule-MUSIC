/**
 * Capsule MUSIC
 * Paxsenix: Apple Music's lyrics, reached through a public relay.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

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

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { isLenient = true; ignoreUnknownKeys = true })
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
            return Result.failure(IllegalStateException("Paxsenix needs a title"))
        }

        val candidates = search(title, artist, album, duration)
        if (candidates.isEmpty()) {
            return Result.failure(IllegalStateException("No Apple Music match for the track"))
        }

        for (track in candidates.take(MAX_TRACKS_TRIED)) {
            val lyrics = fetchLyrics(track.id) ?: continue
            return Result.success(lyrics)
        }
        return Result.failure(IllegalStateException("Matched tracks had no lyrics"))
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
            runCatching {
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

        val body = runCatching { response.body<AppleSearchResponse>() }.getOrNull() ?: return emptyList()
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
            runCatching {
                client.get("$RELAY/apple-music/lyrics") { parameter("id", trackId) }
            }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val body = runCatching { response.body<PaxsenixLyrics>() }.getOrNull() ?: return null
        return body.ttmlContent?.takeIf { it.isNotBlank() }
            ?: body.elrcMultiPerson?.takeIf { it.isNotBlank() }
            ?: body.elrc?.takeIf { it.isNotBlank() }
            ?: body.plain?.takeIf { it.isNotBlank() }
    }

    private suspend fun appleToken(): String? {
        cachedToken?.let { return it }
        return tokenMutex.withLock {
            cachedToken?.let { return@withLock it }
            val token =
                runCatching {
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
