package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableMusixmatchKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * Direct Musixmatch fallback using the same anonymous desktop guest flow the public web client uses.
 *
 * This stays separate from Paxsenix on purpose: when the relay is down, Musixmatch can still answer
 * directly. No user account or cookie is read from the device.
 */
object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    private const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val TOKEN_TTL_MS = 9 * 60 * 1000L

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 12_000
            }
            expectSuccess = false
        }
    }

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMusixmatchKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatchingCancellable {
            if (title.isBlank()) throw NoLyricsFromProvider("Musixmatch needs a title")
            val token = guestToken()

            fetchRichLyrics(token, title, artist, album, duration)
                ?: fetchLrcFallback(token, title, artist, duration)
                ?: throw NoLyricsFromProvider("Musixmatch returned no usable lyrics")
        }

    private suspend fun guestToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAtMs }?.let { return it }

        return tokenMutex.withLock {
            val insideNow = System.currentTimeMillis()
            cachedToken?.takeIf { insideNow < tokenExpiresAtMs }?.let { return@withLock it }

            val response =
                client.get("$BASE/token.get") {
                    commonHeaders()
                    parameter("app_id", APP_ID)
                }
            if (!response.status.isSuccess()) {
                throw NoLyricsFromProvider("Musixmatch token HTTP ${response.status.value}")
            }

            val root = json.parseToJsonElement(response.bodyAsText())
            val token =
                root.path("message", "body", "user_token")
                    ?.asString()
                    ?.takeIf(String::isNotBlank)
                    ?: throw NoLyricsFromProvider("Musixmatch did not issue a guest token")

            cachedToken = token
            tokenExpiresAtMs = insideNow + TOKEN_TTL_MS
            token
        }
    }

    private suspend fun fetchRichLyrics(
        token: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): String? {
        val response =
            client.get("$BASE/macro.subtitles.get") {
                commonHeaders()
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("usertoken", token)
                parameter("q_album", album.orEmpty())
                parameter("q_artist", artist)
                parameter("q_artists", artist)
                parameter("q_track", title)
                if (duration > 0) {
                    parameter("q_duration", duration)
                    parameter("f_subtitle_length", duration)
                }
            }
        if (!response.status.isSuccess()) return null

        val root = runCatching { json.parseToJsonElement(response.bodyAsText()) }.getOrNull() ?: return null
        val subtitleBody =
            root.path(
                "message",
                "body",
                "macro_calls",
                "track.subtitles.get",
                "message",
                "body",
                "subtitle_list",
            )
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.path("subtitle", "subtitle_body")
                ?.asString()
                ?.trim()
                .orEmpty()

        if (subtitleBody.isBlank()) return null
        if (subtitleBody.startsWith("[")) {
            richBodyToLrc(subtitleBody)?.let { return it }
        }
        return subtitleBody.takeIf(String::isNotBlank)
    }

    private suspend fun fetchLrcFallback(
        token: String,
        title: String,
        artist: String,
        duration: Int,
    ): String? {
        val search =
            client.get("$BASE/macro.search") {
                commonHeaders()
                parameter("app_id", APP_ID)
                parameter("page_size", 5)
                parameter("page", 1)
                parameter("s_track_rating", "desc")
                parameter("quorum_factor", "1.0")
                parameter("q", "$title $artist".trim())
                parameter("usertoken", token)
            }
        if (!search.status.isSuccess()) return null

        val root = runCatching { json.parseToJsonElement(search.bodyAsText()) }.getOrNull() ?: return null
        val tracks =
            root.path("message", "body", "macro_result_list", "track_list") as? JsonArray
                ?: return null

        val wantedDurationMs = duration.coerceAtLeast(0) * 1_000L
        val best =
            tracks
                .mapNotNull { item ->
                    val track = item.path("track") as? JsonObject ?: return@mapNotNull null
                    val id = track["track_id"]?.asLong() ?: return@mapNotNull null
                    val name = track["track_name"]?.asString().orEmpty()
                    val foundArtist = track["artist_name"]?.asString().orEmpty()
                    val durationMs =
                        track["track_length"]?.asLong()?.let { seconds ->
                            if (seconds > 10_000L) seconds else seconds * 1_000L
                        } ?: 0L
                    val score =
                        matchScore(
                            foundTitle = name,
                            foundArtist = foundArtist,
                            foundDurationMs = durationMs,
                            title = title,
                            artist = artist,
                            durationMs = wantedDurationMs,
                        )
                    Triple(id, score, durationMs)
                }
                .maxByOrNull { it.second }
                ?.takeIf { it.second > 0 }
                ?: return null

        val response =
            client.get("$BASE/track.subtitle.get") {
                commonHeaders()
                parameter("app_id", APP_ID)
                parameter("subtitle_format", "lrc")
                parameter("track_id", best.first)
                parameter("usertoken", token)
            }
        if (!response.status.isSuccess()) return null

        val subtitleRoot = runCatching { json.parseToJsonElement(response.bodyAsText()) }.getOrNull() ?: return null
        return subtitleRoot
            .path("message", "body", "subtitle", "subtitle_body")
            ?.asString()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun richBodyToLrc(body: String): String? {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        val lines =
            array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val text = obj["text"]?.asString()?.ifBlank { "♪" } ?: return@mapNotNull null
                val time = obj["time"] as? JsonObject ?: return@mapNotNull null
                val minutes = time["minutes"]?.asLong() ?: 0L
                val seconds = time["seconds"]?.asLong() ?: 0L
                val hundredths = time["hundredths"]?.asLong() ?: 0L
                "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, text)
            }
        return lines.joinToString("\n").takeIf(String::isNotBlank)
    }

    private fun matchScore(
        foundTitle: String,
        foundArtist: String,
        foundDurationMs: Long,
        title: String,
        artist: String,
        durationMs: Long,
    ): Int {
        var score = 0
        score +=
            when {
                foundTitle.equals(title, ignoreCase = true) -> 40
                foundTitle.contains(title, ignoreCase = true) ||
                    title.contains(foundTitle, ignoreCase = true) -> 20
                else -> 0
            }
        if (artist.isNotBlank()) {
            score +=
                when {
                    foundArtist.equals(artist, ignoreCase = true) -> 30
                    foundArtist.contains(artist, ignoreCase = true) ||
                        artist.contains(foundArtist, ignoreCase = true) -> 12
                    else -> 0
                }
        }
        if (durationMs > 0L && foundDurationMs > 0L) {
            score +=
                when (abs(durationMs - foundDurationMs)) {
                    in 0L..2_500L -> 30
                    in 2_501L..6_000L -> 15
                    in 6_001L..10_000L -> 5
                    else -> -20
                }
        }
        return score
    }

    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        header("User-Agent", "Mozilla/5.0 Capsule-MUSIC")
        header("Accept", "application/json, text/plain, */*")
        header("Cookie", "AWSELBCORS=0; AWSELB=0;")
    }

    private fun JsonElement.path(vararg keys: String): JsonElement? {
        var current: JsonElement = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.asLong(): Long? =
        (this as? JsonPrimitive)?.longOrNull
}
