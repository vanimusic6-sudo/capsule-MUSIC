/**
 * Capsule MUSIC
 * NetEase Cloud Music: a very large catalogue, for the songs nobody else has.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.runCatchingCancellable
import android.content.Context
import com.nikhil.yt.constants.EnableNetEaseKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.abs

@Serializable
internal data class NetEaseArtist(val name: String = "")

@Serializable
internal data class NetEaseAlbum(val name: String = "")

@Serializable
internal data class NetEaseSong(
    val id: Long = 0,
    val name: String = "",
    /** Milliseconds, unlike the seconds every provider here is handed. */
    val duration: Long = 0,
    val artists: List<NetEaseArtist> = emptyList(),
    val album: NetEaseAlbum? = null,
) : NetEaseSongMatch {
    override val title: String get() = name
    override val artist: String get() = artists.joinToString(" ") { it.name }
    override val durationMs: Long get() = duration
}

@Serializable
private data class NetEaseSearchResult(val songs: List<NetEaseSong> = emptyList())

@Serializable
private data class NetEaseSearchResponse(
    val code: Int = 0,
    val result: NetEaseSearchResult = NetEaseSearchResult(),
)

@Serializable
private data class NetEaseLyric(val lyric: String? = null)

@Serializable
private data class NetEaseLyricResponse(
    val code: Int = 0,
    val lrc: NetEaseLyric? = null,
    val klyric: NetEaseLyric? = null,
)

/**
 * Here for reach rather than for precision.
 *
 * Its catalogue is enormous and it answers for tracks the western sources have never heard of,
 * which is the gap this fills. What it returns is line-timed: the word-level `yrc` field exists in
 * the response but came back empty for every track tried, western and Chinese alike, so it is not
 * read — writing a parser for a format never actually seen is how a provider ends up silently
 * returning nothing.
 *
 * Being line-timed is also why its position in the order matters less than it looks: the helper
 * grades what comes back, so a word-timed answer from anyone else still wins regardless of who was
 * asked first.
 */
object NetEaseLyricsProvider : LyricsProvider {
    override val name = "NetEase"

    private const val BASE = "https://music.163.com/api"
    private const val MAX_RESULTS = 10

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

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableNetEaseKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        if (title.isBlank()) {
            return Result.failure(NoLyricsFromProvider("NetEase needs a title"))
        }

        val matches = search(title, artist, duration)
        if (matches.isEmpty()) {
            return Result.failure(NoLyricsFromProvider("NetEase has no match for the track"))
        }

        for (song in matches) {
            val lyrics = fetchLyrics(song.id) ?: continue
            return Result.success(lyrics)
        }
        return Result.failure(NoLyricsFromProvider("NetEase matched the track but has no lyrics"))
    }

    private suspend fun search(
        title: String,
        artist: String,
        duration: Int,
    ): List<NetEaseSong> {
        val cleanTitle = LyricsQueryCleanup.title(title)
        val cleanArtist = LyricsQueryCleanup.artist(artist)
        val query = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")

        val response =
            runCatchingCancellable {
                client.get("$BASE/search/get") {
                    parameter("s", query)
                    parameter("type", 1)
                    parameter("limit", MAX_RESULTS)
                }
            }.onFailure { Timber.tag(name).d(it, "Search failed") }
                .getOrNull() ?: return emptyList()

        if (!response.status.isSuccess()) return emptyList()

        val body = runCatchingCancellable { response.body<NetEaseSearchResponse>() }.getOrNull() ?: return emptyList()
        return rankNetEaseSongs(body.result.songs, cleanTitle, cleanArtist, duration)
    }

    private suspend fun fetchLyrics(songId: Long): String? {
        val response =
            runCatchingCancellable {
                client.get("$BASE/song/lyric") {
                    parameter("id", songId)
                    parameter("lv", 1)
                    parameter("kv", 1)
                    parameter("tv", -1)
                }
            }.onFailure { Timber.tag(name).d(it, "Lyric fetch failed for %s", songId) }
                .getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val body = runCatchingCancellable { response.body<NetEaseLyricResponse>() }.getOrNull() ?: return null
        return body.lrc?.lyric?.takeIf { it.isNotBlank() }
    }
}

/**
 * Which of the catalogue's answers is most likely to be the same recording.
 *
 * Separated from the network so the matching can actually be tested. Duration carries the most
 * weight because a title and an artist say nothing about which of six versions came back, and a
 * remix or a live take scoring well on name alone is the usual way the wrong lyrics arrive.
 */
internal fun <T : NetEaseSongMatch> rankNetEaseSongs(
    songs: List<T>,
    cleanTitle: String,
    cleanArtist: String,
    durationSeconds: Int,
): List<T> {
    val wantedMs = durationSeconds * 1_000L
    val wantedTitle = LyricsQueryCleanup.title(cleanTitle).lowercase()
    val wantedArtist = cleanArtist.lowercase()
    val wantsRemix = wantedTitle.contains("remix")
    val wantsLive = wantedTitle.contains("live")

    return songs
        .mapNotNull { song ->
            var score = 0.0

            if (wantedMs > 0 && song.durationMs > 0) {
                val gap = abs(song.durationMs - wantedMs)
                /*
                 * A length that is far out is a different recording, not a worse match for this
                 * one, so it is rejected rather than penalised. Penalising it only works until the
                 * title and the artist are both exactly right — which is precisely the case of a
                 * remix, a live take or an extended cut, the ones that most need rejecting.
                 */
                if (gap > MAX_DURATION_GAP_MS) return@mapNotNull null
                // Continuous, so the closest of several plausible lengths actually wins. Buckets
                // made a 147ms miss and a 558ms miss score the same and left the order to chance.
                score += 100.0 * (1.0 - gap.toDouble() / MAX_DURATION_GAP_MS)
            }

            val foundTitle = LyricsQueryCleanup.title(song.title).lowercase()
            when {
                foundTitle == wantedTitle -> score += 80
                foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle) -> score += 40
            }

            if (song.title.contains("remix", ignoreCase = true) && !wantsRemix) score -= 40
            if (song.title.contains("live", ignoreCase = true) && !wantsLive) score -= 40

            if (wantedArtist.isNotBlank() && song.artist.lowercase().contains(wantedArtist)) {
                score += 50
            }

            song to score
        }.filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * How far a candidate's length may be from the track's before it is a different recording.
 *
 * Fifteen seconds covers a fade, a different master and a catalogue rounding its own numbers, and
 * does not cover a remix, a live take or an extended mix.
 */
private const val MAX_DURATION_GAP_MS = 15_000L

/** Just the parts of a catalogue result the ranking looks at. */
internal interface NetEaseSongMatch {
    val title: String
    val artist: String
    val durationMs: Long
}
