/**
 * Capsule MUSIC
 * Deezer lyrics provider adapted from MetroFuse's Deezer rich-sync implementation.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.DeezerCookieKey
import com.nikhil.yt.constants.EnableDeezerLyricsKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Deezer Pipe GraphQL exposes true word-by-word lyrics.
 *
 * Deezer requires an authenticated ARL cookie. Capsule never asks for the user's password: the
 * settings WebView signs in on deezer.com and stores only the resulting cookie string. The ARL is
 * exchanged by Deezer itself for a short-lived JWT, cached for five minutes, then used only against
 * Deezer's lyrics Pipe endpoint.
 */
object DeezerLyricsProvider : LyricsProvider {
    override val name = "Deezer"

    private const val AUTH_URL = "https://auth.deezer.com/login/arl?jo=p&rto=c&i=c"
    private const val PIPE_URL = "https://pipe.deezer.com/api"
    private const val SEARCH_URL = "https://api.deezer.com/search"
    private const val JWT_TTL_MS = 5 * 60 * 1_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/135.0.0.0 Mobile Safari/537.36"

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    private val jwtMutex = Mutex()

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var cachedJwtArl: String? = null

    @Volatile
    private var jwtFetchedAtMs: Long = 0L

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore[EnableDeezerLyricsKey] ?: false) &&
            deezerArlFromInput(context.dataStore[DeezerCookieKey].orEmpty()) != null

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatchingCancellable {
            if (title.isBlank() || artist.isBlank()) {
                throw NoLyricsFromProvider("Deezer needs a title and an artist")
            }

            val context = DeezerLyricsContextHolder.context
                ?: throw NoLyricsFromProvider("Deezer context is not initialized")
            val arl =
                deezerArlFromInput(context.dataStore[DeezerCookieKey].orEmpty())
                    ?: throw NoLyricsFromProvider("Deezer login is required")

            val trackId =
                id.deezerTrackId()
                    ?: searchTrackId(title, artist, duration)
                    ?: throw NoLyricsFromProvider("Deezer has no matching track")

            fetchLyrics(trackId, arl, forceRefreshJwt = false)
                ?: fetchLyrics(trackId, arl, forceRefreshJwt = true)
                ?: throw NoLyricsFromProvider("Deezer returned no usable lyrics")
        }

    /**
     * LyricsProvider does not pass Context to getLyrics. LyricsHelper initializes this once from its
     * application context; keeping only the application object here avoids leaking an Activity.
     */
    internal fun initialize(context: Context) {
        DeezerLyricsContextHolder.context = context.applicationContext
    }

    private object DeezerLyricsContextHolder {
        @Volatile
        var context: Context? = null
    }

    private suspend fun jwtFor(
        arl: String,
        forceRefresh: Boolean,
    ): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedJwt
                ?.takeIf {
                    it.isNotBlank() &&
                        cachedJwtArl == arl &&
                        now - jwtFetchedAtMs < JWT_TTL_MS
                }?.let { return it }
        }

        return jwtMutex.withLock {
            val insideNow = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedJwt
                    ?.takeIf {
                        it.isNotBlank() &&
                            cachedJwtArl == arl &&
                            insideNow - jwtFetchedAtMs < JWT_TTL_MS
                    }?.let { return@withLock it }
            }

            val fresh =
                runCatchingCancellable {
                    val response =
                        client.post(AUTH_URL) {
                            header("User-Agent", USER_AGENT)
                            header("Cookie", "arl=$arl")
                            setBody("")
                        }
                    if (!response.status.isSuccess()) {
                        Timber.tag(name).d("Deezer JWT HTTP %s", response.status.value)
                        return@runCatchingCancellable null
                    }
                    JSONObject(response.bodyAsText())
                        .optString("jwt")
                        .takeIf(String::isNotBlank)
                }.getOrNull()

            if (!fresh.isNullOrBlank()) {
                cachedJwt = fresh
                cachedJwtArl = arl
                jwtFetchedAtMs = insideNow
            }
            fresh
        }
    }

    private fun String.deezerTrackId(): String? =
        Regex("""(?:^deezer:track:|deezer\.com/track/)(\d+)""", RegexOption.IGNORE_CASE)
            .find(trim())
            ?.groupValues
            ?.getOrNull(1)

    private suspend fun searchTrackId(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val query = "$title $artist".trim()
        val url =
            "$SEARCH_URL?q=" +
                URLEncoder.encode(query, Charsets.UTF_8.name()) +
                "&limit=10"

        val response =
            runCatchingCancellable {
                client.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }
            }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val data =
            runCatchingCancellable {
                JSONObject(response.bodyAsText()).optJSONArray("data")
            }.getOrNull() ?: return null

        var bestId: String? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val candidateId =
                item.opt("id")
                    ?.toString()
                    ?.takeIf { value -> value.matches(Regex("""\d+""")) }
                    ?: continue
            val score =
                scoreCandidate(
                    itemTitle = item.optString("title"),
                    itemArtist = item.optJSONObject("artist")?.optString("name").orEmpty(),
                    itemDurationSec = item.optInt("duration", -1),
                    queryTitle = title,
                    queryArtist = artist,
                    queryDurationSec = durationSec,
                )
            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
            }
        }

        return bestId.takeIf { bestScore >= 60 }
    }

    private fun scoreCandidate(
        itemTitle: String,
        itemArtist: String,
        itemDurationSec: Int,
        queryTitle: String,
        queryArtist: String,
        queryDurationSec: Int,
    ): Int {
        fun norm(value: String): String =
            value
                .lowercase()
                .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        val title = norm(itemTitle)
        val wantedTitle = norm(queryTitle)
        val artist = norm(itemArtist)
        val wantedArtist = norm(queryArtist)
        if (title.isBlank() || wantedTitle.isBlank()) return Int.MIN_VALUE

        var score = 0
        score +=
            when {
                title == wantedTitle -> 100
                title.contains(wantedTitle) || wantedTitle.contains(title) -> 75
                else -> {
                    val words = wantedTitle.split(" ").filter { it.length > 3 }
                    if (words.isEmpty()) {
                        0
                    } else {
                        (words.count(title::contains).toFloat() / words.size * 55).toInt()
                    }
                }
            }

        score +=
            when {
                artist == wantedArtist -> 60
                artist.contains(wantedArtist) || wantedArtist.contains(artist) -> 40
                else -> {
                    val words = wantedArtist.split(" ").filter { it.length > 3 }
                    if (words.isEmpty()) {
                        -20
                    } else {
                        (words.count(artist::contains).toFloat() / words.size * 30).toInt()
                    }
                }
            }

        if (queryDurationSec > 30 && itemDurationSec > 30) {
            val diff = abs(queryDurationSec - itemDurationSec)
            score +=
                when {
                    diff < 4 -> 40
                    diff < 12 -> 15
                    diff > 25 -> -60
                    else -> 0
                }
        }

        return score
    }

    private suspend fun fetchLyrics(
        trackId: String,
        arl: String,
        forceRefreshJwt: Boolean,
    ): String? {
        val jwt = jwtFor(arl, forceRefreshJwt) ?: return null
        val payload =
            JSONObject()
                .put("operationName", "GetLyrics")
                .put("variables", JSONObject().put("trackId", trackId))
                .put("query", GET_LYRICS_QUERY)
                .toString()

        val response =
            runCatchingCancellable {
                client.post(PIPE_URL) {
                    header("User-Agent", USER_AGENT)
                    header("Authorization", "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            }.getOrNull() ?: return null

        if (!response.status.isSuccess()) {
            if (response.status.value == 401 || response.status.value == 403) {
                cachedJwt = null
            }
            return null
        }

        val body =
            runCatchingCancellable {
                JSONObject(response.bodyAsText())
            }.getOrNull() ?: return null

        val firstErrorType =
            body.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("type")
        if (firstErrorType == "JwtTokenExpiredError") {
            cachedJwt = null
            return null
        }

        val lyrics =
            body
                .optJSONObject("data")
                ?.optJSONObject("track")
                ?.optJSONObject("lyrics")
                ?: return null

        return wordByWordLrc(lyrics)
            ?: syncedLinesLrc(lyrics)
            ?: lyrics.optString("text").takeIf(String::isNotBlank)
    }

    private fun wordByWordLrc(lyrics: JSONObject): String? =
        runCatching {
            val lines = lyrics.optJSONArray("synchronizedWordByWordLines") ?: return@runCatching null
            if (lines.length() == 0) return@runCatching null

            buildString {
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.optJSONObject(lineIndex) ?: continue
                    val words = line.optJSONArray("words") ?: continue
                    if (words.length() == 0) continue

                    append('[')
                        .append(line.optLong("start").toBracketTimestamp())
                        .append(']')

                    for (wordIndex in 0 until words.length()) {
                        val word = words.optJSONObject(wordIndex) ?: continue
                        val text = word.optString("word").takeIf(String::isNotBlank) ?: continue
                        append('<')
                            .append(word.optLong("start").toBracketTimestamp())
                            .append('>')
                            .append(text)
                            .append(' ')
                    }

                    append('<')
                        .append(line.optLong("end").toBracketTimestamp())
                        .append('>')
                        .append('\n')
                }
            }.trim().ifBlank { null }
        }.getOrNull()

    private fun syncedLinesLrc(lyrics: JSONObject): String? =
        runCatching {
            val lines = lyrics.optJSONArray("synchronizedLines") ?: return@runCatching null
            if (lines.length() == 0) return@runCatching null

            buildString {
                for (index in 0 until lines.length()) {
                    val line = lines.optJSONObject(index) ?: continue
                    val text = line.optString("line").takeIf(String::isNotBlank) ?: continue
                    append('[')
                        .append(line.optLong("milliseconds").toBracketTimestamp())
                        .append(']')
                        .append(text)
                        .append('\n')
                }
            }.trim().ifBlank { null }
        }.getOrNull()

    private fun Long.toBracketTimestamp(): String {
        val totalCenti = (this / 10L).coerceAtLeast(0L)
        return "%02d:%02d.%02d".format(
            totalCenti / 6000L,
            (totalCenti % 6000L) / 100L,
            totalCenti % 100L,
        )
    }

    private const val GET_LYRICS_QUERY =
        "query GetLyrics(\$trackId: String!) { track(trackId: \$trackId) { id lyrics { id text " +
            "...SynchronizedWordByWordLines ...SynchronizedLines licence copyright writers __typename } __typename } } " +
            "fragment SynchronizedWordByWordLines on Lyrics { id synchronizedWordByWordLines { start end " +
            "words { start end word __typename } __typename } __typename } " +
            "fragment SynchronizedLines on Lyrics { id synchronizedLines { lrcTimestamp line lineTranslated " +
            "milliseconds duration __typename } __typename }"
}

internal fun deezerArlFromInput(input: String): String? {
    val raw =
        input
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
    if (raw.isBlank()) return null

    val candidates =
        listOfNotNull(
            raw,
            runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull(),
        ).distinct()

    candidates.forEach { candidate ->
        candidate
            .split(';', '\n', '\r')
            .map(String::trim)
            .firstOrNull { it.startsWith("arl=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }

    return raw.takeIf {
        it.length >= 100 &&
            it.none(Char::isWhitespace) &&
            !it.contains(';') &&
            !it.contains('=') &&
            !it.contains('{') &&
            !it.contains('}')
    }
}
