/**
 * Capsule MUSIC
 * LyricsPlus: community word-synced lyrics, served by a rotating set of mirrors.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.runCatchingCancellable
import android.content.Context
import com.nikhil.yt.constants.EnableLyricsPlusKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

@Serializable
private data class LyricsPlusWord(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
)

@Serializable
private data class LyricsPlusLine(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
    val syllabus: List<LyricsPlusWord>? = null,
)

@Serializable
private data class LyricsPlusResponse(
    val type: String? = null,
    val lyrics: List<LyricsPlusLine>? = null,
)

object LyricsPlusLyricsProvider : LyricsProvider {
    override val name = "LyricsPlus"

    /**
     * Mirrors of the same service, tried in turn.
     *
     * They are volunteer-run and take it in turns to be down — at the time of writing exactly one
     * of these answered, a different one from the one the upstream project lists first. A single
     * base URL here would mean the provider is dead whenever that particular box is, which is most
     * of the time for any one of them.
     */
    private val baseUrls =
        listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus-seven.vercel.app",
        )

    /** Whichever mirror answered last goes first next time, so a working one is not re-discovered. */
    @Volatile
    private var lastWorkingServer: String? = null

    /**
     * When every mirror last failed, so a service that is simply down is asked about once.
     *
     * These are volunteer boxes and they go down together. With no memory of that, every track
     * opened three connections that could not be made and waited out their timeouts before the
     * provider could be passed over — on a device where nothing answered, that is three failed
     * requests per song, forever. A capture of ordinary listening showed exactly that: eight songs,
     * eight rounds of it, not one answer.
     *
     * After a whole round fails, the provider stands down for [OUTAGE_COOLDOWN_MS] and reports
     * having nothing without touching the network. Any answer at all clears it immediately, so a
     * mirror coming back is picked up on the next song rather than after some long penalty.
     */
    @Volatile
    private var allMirrorsFailedAtMs: Long = 0L

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
        context.dataStore[EnableLyricsPlusKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        if (title.isBlank() || artist.isBlank()) {
            return Result.failure(NoLyricsFromProvider("LyricsPlus needs a title and an artist"))
        }
        val sinceOutage = System.currentTimeMillis() - allMirrorsFailedAtMs
        if (allMirrorsFailedAtMs != 0L && sinceOutage in 0 until OUTAGE_COOLDOWN_MS) {
            return Result.failure(NoLyricsFromProvider("LyricsPlus is down; not asking again yet"))
        }

        val response =
            fetch(title, artist, album, duration)
                ?: return Result.failure(NoLyricsFromProvider("No LyricsPlus mirror answered"))
        val lrc =
            toLrc(response)
                ?: return Result.failure(NoLyricsFromProvider("LyricsPlus returned no usable lines"))
        return Result.success(lrc)
    }

    private fun prioritizedServers(): List<String> {
        val last = lastWorkingServer
        return if (last != null && last in baseUrls) {
            listOf(last) + baseUrls.filterNot { it == last }
        } else {
            baseUrls
        }
    }

    private suspend fun fetch(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): LyricsPlusResponse? {
        for (baseUrl in prioritizedServers()) {
            val result =
                runCatchingCancellable {
                    val response =
                        client.get("$baseUrl/v2/lyrics/get") {
                            parameter("title", title)
                            parameter("artist", artist)
                            // The API wants seconds; MediaMetadata already stores seconds here.
                            if (duration > 0) parameter("duration", duration)
                            if (!album.isNullOrBlank()) parameter("album", album)
                        }
                    if (response.status == HttpStatusCode.OK) {
                        response.body<LyricsPlusResponse>()
                    } else {
                        null
                    }
                }.onFailure { Timber.tag(name).d(it, "Mirror failed: %s", baseUrl) }
                    .getOrNull()

            if (result?.lyrics?.isNotEmpty() == true) {
                lastWorkingServer = baseUrl
                allMirrorsFailedAtMs = 0L
                return result
            }
        }
        allMirrorsFailedAtMs = System.currentTimeMillis()
        return null
    }

    /** Long enough that an outage costs one round of requests rather than one per song. */
    internal const val OUTAGE_COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * Enhanced LRC, so the per-word timings survive.
     *
     * This used to emit plain line-level LRC and drop the word data, because the LRC parser had
     * nowhere to put it — only TTML did. It has somewhere now, and that matters more here than
     * anywhere: word timing is the difference between lyrics that merely keep up and lyrics that
     * land on the syllable.
     *
     *     [00:00.55]<00:00.55>My <00:00.90>day <00:01.83>will come<00:03.19>
     *
     * The trailing stamp is where the last word ends, which the response knows and plain LRC has
     * no way to say.
     */
    private fun toLrc(response: LyricsPlusResponse): String? {
        val lines = response.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val lrc =
            lines
                .mapNotNull { line -> lineToLrc(line) }
                .joinToString("\n")
        return lrc.ifBlank { null }
    }

    private fun lineToLrc(line: LyricsPlusLine): String? {
        val start = line.time.coerceAtLeast(0L)
        val words = line.syllabus.orEmpty().filter { it.text.isNotBlank() }

        if (words.isEmpty()) {
            val text = line.text.trim()
            if (text.isEmpty()) return null
            return stamp(start, squareBrackets = true) + text
        }

        return buildString {
            append(stamp(start, squareBrackets = true))
            words.forEach { word ->
                append(stamp(word.time.coerceAtLeast(0L), squareBrackets = false))
                append(word.text)
            }
            // Where the last word stops. Without it the renderer has to guess.
            val last = words.last()
            val end = (last.time + last.duration).coerceAtLeast(last.time)
            append(stamp(end, squareBrackets = false))
        }
    }

    private fun stamp(
        timeMs: Long,
        squareBrackets: Boolean,
    ): String {
        val minutes = timeMs / 60_000
        val seconds = (timeMs / 1_000) % 60
        val hundredths = (timeMs % 1_000) / 10
        val body = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
        return if (squareBrackets) "[$body]" else "<$body>"
    }
}
