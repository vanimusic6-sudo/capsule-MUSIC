/* Capsule MUSIC — Better Lyrics QQ / Portato word-synced fallback. GPL-3.0. */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableBetterLyricsPortatoKey
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

/**
 * Portato uses /qq/getLyrics, not the auth-gated TTML cache. It returns QRC in an XML
 * envelope, which QrcToEnhancedLrc converts to Capsule's existing word-timed LRC format.
 */
object BetterLyricsPortatoProvider : LyricsProvider {
    override val name = "Better Lyrics: Portato (QQ)"

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_500
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 5_500
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableBetterLyricsPortatoKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = try {
        if (title.isBlank() || artist.isBlank()) {
            Result.failure(NoLyricsFromProvider("QQ lyrics need a title and artist"))
        } else {
            val response = client.get("https://api.betterlyrics.org/qq/getLyrics") {
                parameter("s", title)
                parameter("a", artist)
                parameter("al", album.orEmpty())
                parameter("d", duration.coerceAtLeast(0))
            }
            if (!response.status.isSuccess()) {
                Result.failure(NoLyricsFromProvider("QQ lyrics status: " + response.status.value))
            } else {
                val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                val qrc = (root?.get("lyrics") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val lyrics = QrcToEnhancedLrc.convert(qrc, title, artist)
                if (!lyrics.isNullOrBlank()) Result.success(lyrics)
                else Result.failure(NoLyricsFromProvider("QQ returned no renderable lyrics"))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
