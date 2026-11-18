/* Capsule MUSIC — public SimpMusic community lyrics lookup. GPL-3.0. */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableSimpMusicKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * Video-ID lookup is the fastest/least ambiguous path for Capsule's YouTube music catalog.
 * Bounded metadata search is used only on a miss; inaccurate remixes and live recordings
 * are rejected rather than silently substituting the wrong synced lyrics.
 */
object SimpMusicLyricsProvider : LyricsProvider {
    override val name = "SimpMusic"

    private const val ENDPOINT = "https://api-lyrics.simpmusic.org/v1"
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val punctuation = Regex("[^\\p{L}\\p{N}]")
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 3_000
                connectTimeoutMillis = 2_000
                socketTimeoutMillis = 3_000
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableSimpMusicKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = try {
        if (title.isBlank() || artist.isBlank()) {
            Result.failure(NoLyricsFromProvider("SimpMusic needs title and artist"))
        } else {
            val exact = if (videoIdPattern.matches(id)) fetchByVideoId(id) else emptyList()
            val direct = exact.firstOrNull { it.videoId == id && it.lyrics != null }?.lyrics
            if (direct != null) {
                Result.success(direct)
            } else {
                // The ID may be a playlist/album entry, or this particular video may not have
                // a submission. Search is limited to a small page; quality beats false matches.
                val candidates = search(title, artist)
                val selected = candidates
                    .filter { it.lyrics != null && matches(it, title, artist, duration) }
                    .minByOrNull { item ->
                        val delta = if (duration > 0 && item.durationSeconds > 0) {
                            abs(duration - item.durationSeconds)
                        } else 0
                        delta + if (item.hasSyncedLyrics) 0 else 30
                    }
                selected?.lyrics?.let { Result.success(it) }
                    ?: Result.failure(NoLyricsFromProvider("No matching SimpMusic lyric"))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun fetchByVideoId(id: String): List<Entry> {
        val response = client.get("$ENDPOINT/$id")
        return if (response.status.isSuccess()) parseEntries(response.bodyAsText()) else emptyList()
    }

    private suspend fun search(title: String, artist: String): List<Entry> {
        val response = client.get("$ENDPOINT/search") {
            parameter("q", "$title $artist")
            parameter("limit", 8)
        }
        return if (response.status.isSuccess()) parseEntries(response.bodyAsText()) else emptyList()
    }

    private data class Entry(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSeconds: Int,
        val lyrics: String?,
        val hasSyncedLyrics: Boolean,
    )

    private fun parseEntries(payload: String): List<Entry> {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
        val rows = root["data"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val synced = sequenceOf(row.string("richSyncLyrics"), row.string("syncedLyrics"))
                .firstOrNull { it.isNotBlank() && (it.trimStart().startsWith("[") || it.contains("<tt")) }
            val plain = row.string("plainLyric").takeIf { it.isNotBlank() }
            Entry(
                videoId = row.string("videoId"),
                title = row.string("songTitle"),
                artist = row.string("artistName"),
                durationSeconds = row.string("durationSeconds").toIntOrNull() ?: 0,
                lyrics = synced ?: plain,
                hasSyncedLyrics = synced != null,
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun matches(item: Entry, title: String, artist: String, duration: Int): Boolean {
        val desiredTitle = simplify(title)
        val foundTitle = simplify(item.title)
        val desiredArtist = simplify(artist)
        val foundArtist = simplify(item.artist)
        if (desiredTitle.isBlank() || foundTitle.isBlank()) return false
        if (desiredTitle != foundTitle &&
            !desiredTitle.startsWith(foundTitle) && !foundTitle.startsWith(desiredTitle)) return false
        if (desiredArtist.isNotBlank() && foundArtist.isNotBlank() &&
            !foundArtist.contains(desiredArtist) && !desiredArtist.contains(foundArtist)) return false
        return duration <= 0 || item.durationSeconds <= 0 ||
            abs(duration - item.durationSeconds) <= 22
    }

    private fun simplify(text: String): String =
        text.lowercase().replace(punctuation, "")
}
