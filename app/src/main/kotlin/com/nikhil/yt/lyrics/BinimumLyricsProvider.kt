/*
 * Capsule MUSIC — secondary TTML catalog (BiniLyrics).
 * API contract studied from GPL-3.0 ArchiveTune provider; HTTPS destination
 * checks are intentionally stricter. GPL-3.0.
 */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableBinimumLyricsKey
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
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import kotlin.math.abs

/** API-provided download URLs must never become arbitrary hosts (SSRF defense). */
internal fun safeBinimumLyricsUrl(raw: String): String? {
    if (raw.length !in 1..2048) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in listOf(-1, 443) || uri.host !in setOf(
            "lyrics-storage.binimum.org", "lyrics-api.binimum.org",
        )
    ) return null
    return uri.toASCIIString()
}

internal fun selectBinimumLyricsUrl(body: String, durationSeconds: Int): String? {
    if (body.length > 256_000) return null
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return null
    val results = root["results"] as? JsonArray ?: return null
    return results.asSequence().take(20).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val address = (obj["lyricsUrl"] as? JsonPrimitive)?.contentOrNull
            ?.let(::safeBinimumLyricsUrl) ?: return@mapNotNull null
        val knownDuration = (obj["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (knownDuration != null && (knownDuration <= 0 || !knownDuration.isFinite())) return@mapNotNull null
        if (durationSeconds > 0 && knownDuration != null &&
            abs(knownDuration - durationSeconds) > 6.0) return@mapNotNull null
        val isWord = (obj["timing_type"] as? JsonPrimitive)?.contentOrNull == "word"
        Triple(address, if (isWord) 0 else 1,
            if (knownDuration != null && durationSeconds > 0)
                abs(knownDuration - durationSeconds) else 999.0)
    }.sortedWith(compareBy<Triple<String, Int, Double>> { it.second }.thenBy { it.third })
        .firstOrNull()?.first
}

object BinimumLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics · TTML"
    private val client by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 6_500L
                connectTimeoutMillis = 3_000L
                socketTimeoutMillis = 6_500L
            }
        }
    }

    override fun isEnabled(context: Context) =
        context.dataStore[EnableBinimumLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String, title: String, artist: String, album: String?, duration: Int,
    ): Result<String> = try {
        if (title.isBlank() || artist.isBlank()) {
            Result.failure(NoLyricsFromProvider("BiniLyrics needs title and artist"))
        } else {
            val index = client.get("https://lyrics-api.binimum.org/") {
                parameter("track", LyricsQueryCleanup.title(title))
                parameter("artist", LyricsQueryCleanup.artist(artist))
                if (!album.isNullOrBlank()) parameter("album", album)
                if (duration > 0) parameter("duration", duration)
            }
            if (!index.status.isSuccess()) {
                Result.failure(NoLyricsFromProvider("BiniLyrics index HTTP " + index.status.value))
            } else {
                val url = selectBinimumLyricsUrl(index.bodyAsText(), duration)
                if (url == null) Result.failure(NoLyricsFromProvider("BiniLyrics returned no safe match"))
                else {
                    val result = client.get(url)
                    if (!result.status.isSuccess()) {
                        Result.failure(NoLyricsFromProvider("BiniLyrics TTML HTTP " + result.status.value))
                    } else {
                        val text = result.bodyAsText()
                        if (text.length in 1..512_000 && LyricsUtils.isTtml(text) &&
                            LyricsUtils.parseTtml(text).any { it.text.isNotBlank() }
                        ) Result.success(text)
                        else Result.failure(NoLyricsFromProvider("BiniLyrics TTML was empty or invalid"))
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
