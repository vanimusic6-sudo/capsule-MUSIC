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
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

@Serializable
internal data class LyricsPlusWord(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
)

@Serializable
internal data class LyricsPlusLine(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
    val syllabus: List<LyricsPlusWord>? = null,
)

@Serializable
internal data class LyricsPlusResponse(
    val type: String? = null,
    val lyrics: List<LyricsPlusLine>? = null,
    // Mirrors sometimes return the direct LRC or plain fields instead of KPOE.
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
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
            // Current public mirrors listed by upstream clients (2026).
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyrics-plus-backend.vercel.app",
            "https://lyricsplus.binimum.org",
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
                requestTimeoutMillis = 5_500
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 5_500
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

    /**
     * Staggered first-answer search. The old serial fallback exhausted the
     * manual picker's overall timeout before even reaching mirror #3.
     * Successful fast mirrors cancel all scheduled backups; slow/down mirrors
     * start only the minimum additional requests needed.
     */
    private suspend fun fetch(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): LyricsPlusResponse? = supervisorScope {
        val servers = prioritizedServers()
        data class Outcome(
            val server: String,
            val answered: Boolean,
            val lyrics: LyricsPlusResponse?,
        )
        val completed = Channel<Outcome>(Channel.UNLIMITED)
        val workers = servers.mapIndexed { index, server ->
            launch(Dispatchers.IO) {
                delay(index * 550L)
                val outcome = runCatchingCancellable {
                    val response = client.get("$server/v2/lyrics/get") {
                        parameter("title", LyricsQueryCleanup.title(title))
                        parameter("artist", LyricsQueryCleanup.artist(artist))
                        if (duration > 0) parameter("duration", duration)
                        if (!album.isNullOrBlank()) parameter("album", album)
                        header(HttpHeaders.Accept, "application/json")
                        header("X-Client-Package",
                            "CapsuleMusic <https://github.com/vanimusic6-sudo/capsule-MUSIC>")
                    }
                    val reached = response.status == HttpStatusCode.OK ||
                        response.status == HttpStatusCode.NotFound
                    val body = if (response.status == HttpStatusCode.OK)
                        response.body<LyricsPlusResponse>() else null
                    Outcome(server, reached, body)
                }.onFailure { Timber.tag(name).d(it, "Mirror failed: %s", server) }
                    .getOrNull() ?: Outcome(server, false, null)
                completed.send(outcome)
            }
        }
        var answered = false
        try {
            repeat(servers.size) {
                val next = completed.receive()
                answered = answered || next.answered
                val data = next.lyrics
                if (data != null && toLrc(data) != null) {
                    lastWorkingServer = next.server
                    allMirrorsFailedAtMs = 0L
                    return@supervisorScope data
                }
            }
            allMirrorsFailedAtMs = if (answered) 0L else System.currentTimeMillis()
            null
        } finally {
            workers.forEach { it.cancel() }
            completed.close()
        }
    }

    /** Long enough that an outage costs one round of requests rather than one per song. */
    internal const val OUTAGE_COOLDOWN_MS = 90 * 1000L

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
    internal fun toLrc(response: LyricsPlusResponse): String? {
        response.syncedLyrics?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        val lines = response.lyrics?.takeIf { it.isNotEmpty() }
            ?: return response.plainLyrics?.trim()?.takeIf(String::isNotBlank)
        val lrc =
            lines
                .mapNotNull { line -> lineToLrc(line) }
                .joinToString("\n")
        return lrc.ifBlank { response.plainLyrics?.trim()?.takeIf(String::isNotBlank) }
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
