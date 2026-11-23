/*
 * Capsule MUSIC — KuGou public LRC provider via the documented Better Lyrics
 * provider-specific endpoint. GPL-3.0; no API key or added dependency.
 */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableKuGouLyricsKey
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun extractKugouLrc(body: String): String? {
    if (body.length > 512_000) return null
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return null
    val source = (root["provider"] as? JsonPrimitive)?.contentOrNull
    if (source != "kugou") return null
    val lyrics = (root["lyrics"] as? JsonPrimitive)?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 200_000 } ?: return null
    // Reject a misleading success response containing only metadata, not timed LRC.
    if (!Regex("""(?m)^\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]""").containsMatchIn(lyrics)) return null
    return lyrics
}

object KugouLyricsProvider : LyricsProvider {
    override val name = "KuGou · LRC"
    private val client by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000L
                connectTimeoutMillis = 3_000L
                socketTimeoutMillis = 6_000L
            }
        }
    }

    override fun isEnabled(context: Context) =
        context.dataStore[EnableKuGouLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String, title: String, artist: String, album: String?, duration: Int,
    ): Result<String> = try {
        if (title.isBlank() || artist.isBlank() || !BetterLyricsPublicLimit.mayRequest()) {
            Result.failure(NoLyricsFromProvider("KuGou unavailable or public API cooling down"))
        } else {
            val response = client.get("https://api.betterlyrics.org/kugou/getLyrics") {
                parameter("s", LyricsQueryCleanup.title(title))
                parameter("a", artist.trim())
                if (!album.isNullOrBlank()) parameter("al", album.trim())
                if (duration > 0) parameter("d", duration)
            }
            BetterLyricsPublicLimit.noteResponse(response.status.value, response.headers["Retry-After"])
            if (!response.status.isSuccess()) {
                Result.failure(NoLyricsFromProvider("KuGou returned HTTP " + response.status.value))
            } else {
                val lyrics = extractKugouLrc(response.bodyAsText())
                if (lyrics == null) Result.failure(NoLyricsFromProvider("KuGou has no timed lyrics"))
                else Result.success(lyrics)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
