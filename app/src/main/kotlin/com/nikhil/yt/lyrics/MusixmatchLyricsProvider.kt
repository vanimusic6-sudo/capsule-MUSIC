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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Direct Musixmatch fallback using the anonymous desktop guest flow.
 *
 * The important bit is that the subtitle is never trusted on its own. Musixmatch can return a
 * technically valid payload for the wrong match, an exhausted guest token, or an obfuscated filler
 * body. We first verify matcher.track.get against the requested title/artist/duration, prefer
 * richsync for that exact commontrack_id, then accept line LRC only after sanity checks.
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
            if (title.isBlank() || artist.isBlank()) {
                throw NoLyricsFromProvider("Musixmatch needs a title and an artist")
            }

            var forceNewToken = false
            repeat(2) {
                val token = guestToken(force = forceNewToken)
                when (val result = fetchMatchedLyrics(token, title, artist, album, duration)) {
                    is FetchResult.Lyrics -> return@runCatchingCancellable result.value
                    FetchResult.TokenExpired -> {
                        invalidateToken()
                        forceNewToken = true
                    }
                    FetchResult.Miss -> {
                        throw NoLyricsFromProvider("Musixmatch returned no trusted lyrics")
                    }
                }
            }

            throw NoLyricsFromProvider("Musixmatch guest token was rejected")
        }

    private sealed interface FetchResult {
        data class Lyrics(val value: String) : FetchResult
        data object TokenExpired : FetchResult
        data object Miss : FetchResult
    }

    private suspend fun guestToken(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force) {
            cachedToken?.takeIf { now < tokenExpiresAtMs }?.let { return it }
        }

        return tokenMutex.withLock {
            val insideNow = System.currentTimeMillis()
            if (!force) {
                cachedToken?.takeIf { insideNow < tokenExpiresAtMs }?.let { return@withLock it }
            }

            val response =
                client.get("$BASE/token.get") {
                    commonHeaders()
                    parameter("app_id", APP_ID)
                }
            if (!response.status.isSuccess()) {
                throw NoLyricsFromProvider("Musixmatch token HTTP ${response.status.value}")
            }

            val root =
                runCatching { json.parseToJsonElement(response.bodyAsText()) }.getOrNull()
                    ?: throw NoLyricsFromProvider("Musixmatch token response was not JSON")

            val token =
                root.deepFind("user_token")
                    ?.asString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") }
                    ?: throw NoLyricsFromProvider("Musixmatch did not issue a usable guest token")

            cachedToken = token
            tokenExpiresAtMs = insideNow + TOKEN_TTL_MS
            token
        }
    }

    private fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMs = 0L
    }

    private suspend fun fetchMatchedLyrics(
        token: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): FetchResult {
        val response =
            client.get("$BASE/macro.subtitles.get") {
                commonHeaders()
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "lrc")
                parameter("app_id", APP_ID)
                parameter("usertoken", token)
                parameter("q_track", title)
                parameter("q_artist", artist)
                parameter("q_album", album.orEmpty())
                if (duration > 0) parameter("q_duration", duration)
            }

        if (!response.status.isSuccess()) {
            return if (response.status.value == 401) FetchResult.TokenExpired else FetchResult.Miss
        }

        val raw = response.bodyAsText()
        if (raw.trimStart().startsWith("<")) return FetchResult.Miss

        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return FetchResult.Miss
        val apiStatus = root.deepFind("status_code")?.asLong()?.toInt()
        if (apiStatus == 401) return FetchResult.TokenExpired
        if (apiStatus != null && apiStatus !in 200..299) return FetchResult.Miss

        val matcherCall = root.deepFind("matcher.track.get") ?: return FetchResult.Miss
        val track = matcherCall.deepFind("track") as? JsonObject ?: return FetchResult.Miss
        if (!isTrustedMatch(track, title, artist, duration)) return FetchResult.Miss
        if (track["instrumental"]?.asBoolean() == true) return FetchResult.Miss

        val commonTrackId = track["commontrack_id"]?.asLong() ?: 0L
        val hasRichSync = track["has_richsync"]?.asLong() == 1L
        if (hasRichSync && commonTrackId > 0L) {
            fetchRichSync(token, commonTrackId, duration)?.let {
                return FetchResult.Lyrics(it)
            }
        }

        val subtitle =
            root.deepFind("subtitle_body")
                ?.asString()
                ?.trim()
                ?.takeIf { it.contains('[') && it.isNotBlank() }
                ?: return FetchResult.Miss

        return validateLyrics(subtitle, duration)
            ?.let(FetchResult::Lyrics)
            ?: FetchResult.Miss
    }

    private suspend fun fetchRichSync(
        token: String,
        commonTrackId: Long,
        duration: Int,
    ): String? {
        val response =
            client.get("$BASE/track.richsync.get") {
                commonHeaders()
                parameter("app_id", APP_ID)
                parameter("usertoken", token)
                parameter("commontrack_id", commonTrackId)
            }
        if (!response.status.isSuccess()) return null

        val raw = response.bodyAsText()
        if (raw.trimStart().startsWith("<")) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val body = root.deepFind("richsync_body")?.asString()?.takeIf(String::isNotBlank) ?: return null
        val enhanced = richSyncToEnhancedLrc(body) ?: return null
        return validateLyrics(enhanced, duration)
    }

    private fun isTrustedMatch(
        track: JsonObject,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Boolean {
        val foundTitle = track["track_name"]?.asString().orEmpty()
        val foundArtist = track["artist_name"]?.asString().orEmpty()
        val foundDuration = track["track_length"]?.asLong()?.toInt() ?: 0

        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val gotTitle = normalize(foundTitle)
        val gotArtist = normalize(foundArtist)

        val titleOk =
            gotTitle == wantedTitle ||
                gotTitle.contains(wantedTitle) ||
                wantedTitle.contains(gotTitle)
        val artistOk =
            gotArtist == wantedArtist ||
                gotArtist.contains(wantedArtist) ||
                wantedArtist.contains(gotArtist)

        val durationGap =
            if (durationSeconds > 0 && foundDuration > 0) {
                abs(durationSeconds - foundDuration)
            } else {
                Int.MAX_VALUE
            }

        return (titleOk && artistOk) ||
            (durationGap <= 4 && (titleOk || artistOk))
    }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun richSyncToEnhancedLrc(body: String): String? {
        val lines = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        val result =
            lines.mapNotNull { element ->
                val line = element as? JsonObject ?: return@mapNotNull null
                val lineStartSeconds = line["ts"]?.asDouble() ?: return@mapNotNull null
                val chunks = line["l"] as? JsonArray ?: return@mapNotNull null
                val fallback = line["x"]?.asString().orEmpty()

                val words =
                    buildString {
                        chunks.forEach { chunkElement ->
                            val chunk = chunkElement as? JsonObject ?: return@forEach
                            val text = chunk["c"]?.asString().orEmpty()
                            if (text.isEmpty()) return@forEach
                            val offsetSeconds = chunk["o"]?.asDouble() ?: 0.0
                            append(angleStamp(((lineStartSeconds + offsetSeconds) * 1000.0).roundToLong()))
                            append(text)
                        }
                    }.ifBlank { fallback }

                if (words.isBlank()) return@mapNotNull null
                squareStamp((lineStartSeconds * 1000.0).roundToLong()) + words
            }

        return result.joinToString("\n").takeIf(String::isNotBlank)
    }

    private fun validateLyrics(
        lyrics: String,
        durationSeconds: Int,
    ): String? {
        val normalized = lyrics.trim()
        if (normalized.isBlank()) return null
        if (isLikelySyntheticPlaceholder(normalized)) return null

        if (durationSeconds > 0) {
            val lastTimestamp = LRC_LINE_REGEX.findAll(normalized).mapNotNull { it.timestampMs() }.maxOrNull()
            if (lastTimestamp != null && lastTimestamp > durationSeconds * 1_000L + 15_000L) {
                return null
            }
        }
        return normalized
    }

    /**
     * Musixmatch occasionally answers anonymous clients with a syntactically valid but artificial
     * LRC body: hundreds of three-word pseudo-language lines at a perfectly fixed cadence. It must
     * be treated as a miss, otherwise it outranks every real provider simply because it parses.
     */
    internal fun isLikelySyntheticPlaceholder(lyrics: String): Boolean {
        val parsed =
            lyrics
                .lineSequence()
                .mapNotNull { line ->
                    val match = LRC_LINE_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
                    val timestamp = match.timestampMs() ?: return@mapNotNull null
                    val text = match.groupValues[4].trim()
                    timestamp to text
                }.toList()

        if (parsed.size < 40) return false

        val gaps = parsed.zipWithNext { a, b -> b.first - a.first }
        val regularFourSecondRatio =
            if (gaps.isEmpty()) 0f else gaps.count { abs(it - 4_000L) <= 30L }.toFloat() / gaps.size
        val pseudoWordRatio =
            parsed.count { (_, text) ->
                val words = text.split(Regex("""\s+""")).filter(String::isNotBlank)
                words.size in 2..4 &&
                    words.all { word -> word.length in 2..8 && word.all(Char::isLetter) }
            }.toFloat() / parsed.size
        val punctuationRatio =
            parsed.count { (_, text) -> text.any { !it.isLetterOrDigit() && !it.isWhitespace() } }
                .toFloat() / parsed.size

        return regularFourSecondRatio >= 0.90f &&
            pseudoWordRatio >= 0.85f &&
            punctuationRatio <= 0.05f
    }

    private fun squareStamp(millis: Long): String = stamp(millis, '[', ']')

    private fun angleStamp(millis: Long): String = stamp(millis, '<', '>')

    private fun stamp(
        millis: Long,
        open: Char,
        close: Char,
    ): String {
        val safe = millis.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1_000L) % 60L
        val hundredths = (safe % 1_000L) / 10L
        return "%c%02d:%02d.%02d%c".format(open, minutes, seconds, hundredths, close)
    }

    private fun MatchResult.timestampMs(): Long? {
        val minutes = groupValues[1].toLongOrNull() ?: return null
        val seconds = groupValues[2].toLongOrNull() ?: return null
        val fraction = groupValues[3]
        val millis =
            when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100L)
                2 -> fraction.toLongOrNull()?.times(10L)
                else -> fraction.take(3).padEnd(3, '0').toLongOrNull()
            } ?: return null
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        header(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Musixmatch/0.19.4 Chrome/58.0.3029.110 Electron/1.7.6 Safari/537.36",
        )
        header("Accept", "application/json, text/plain, */*")
        header("Accept-Language", "en-US,en;q=0.9")
        header("Cookie", "AWSELBCORS=0; AWSELB=0;")
    }

    private fun JsonElement.deepFind(key: String): JsonElement? {
        when (this) {
            is JsonObject -> {
                this[key]?.let { return it }
                values.forEach { child -> child.deepFind(key)?.let { return it } }
            }
            is JsonArray -> forEach { child -> child.deepFind(key)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.asLong(): Long? =
        (this as? JsonPrimitive)?.longOrNull

    private fun JsonElement.asDouble(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement.asBoolean(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull

    private val LRC_LINE_REGEX =
        Regex("""^\[(\d{1,3}):(\d{2})[.:](\d{1,3})](.*)$""")
}
