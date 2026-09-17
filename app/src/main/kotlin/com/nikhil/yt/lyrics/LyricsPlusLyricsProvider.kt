/**
 * Capsule MUSIC
 * LyricsPlus: community word-synced lyrics, served by a rotating set of mirrors.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

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
            return Result.failure(IllegalStateException("LyricsPlus needs a title and an artist"))
        }
        val response =
            fetch(title, artist, album, duration)
                ?: return Result.failure(IllegalStateException("No LyricsPlus mirror answered"))
        val lrc =
            toLrc(response)
                ?: return Result.failure(IllegalStateException("LyricsPlus returned no usable lines"))
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
                runCatching {
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
                return result
            }
        }
        return null
    }

    /**
     * Plain LRC, deliberately.
     *
     * The response carries per-word timings and multi-voice agents, and our own renderer can use
     * both — but only through TTML, because LRC parsing here has never had a place to put them. So
     * rather than inventing an extended dialect that the parser would hand back as literal
     * "{agent:v1}" in the middle of a line, this emits the timing every part of the app already
     * understands, and the word data is dropped on purpose rather than mangled.
     */
    private fun toLrc(response: LyricsPlusResponse): String? {
        val lines = response.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val lrc =
            lines
                .mapNotNull { line ->
                    val text = line.text.trim().ifEmpty {
                        line.syllabus?.joinToString("") { it.text }?.trim().orEmpty()
                    }
                    if (text.isEmpty()) return@mapNotNull null
                    val time = line.time.coerceAtLeast(0L)
                    val minutes = time / 60_000
                    val seconds = (time / 1_000) % 60
                    val hundredths = (time % 1_000) / 10
                    String.format(Locale.US, "[%02d:%02d.%02d]%s", minutes, seconds, hundredths, text)
                }.joinToString("\n")
        return lrc.ifBlank { null }
    }
}
