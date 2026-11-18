/* Capsule MUSIC — independent KuGou lyrics catalog via Better Lyrics. GPL-3.0. */
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

/** KuGou has its own song catalog, unlike an extra TTML aggregate mirror. */
object BetterLyricsKuGouProvider : LyricsProvider {
    override val name = "KuGou"
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 5_000
            }
            expectSuccess = false
        }
    }
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKuGouLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = try {
        if (title.isBlank() || artist.isBlank()) {
            Result.failure(NoLyricsFromProvider("KuGou needs a title and artist"))
        } else {
            val response = client.get("https://api.betterlyrics.org/kugou/getLyrics") {
                parameter("s", title)
                parameter("a", artist)
                if (!album.isNullOrBlank()) parameter("al", album)
                if (duration > 0) parameter("d", duration)
            }
            if (!response.status.isSuccess()) {
                Result.failure(NoLyricsFromProvider("KuGou status: ${response.status.value}"))
            } else {
                val root = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                val lrc = (root?.get("lyrics") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val usable = lrc.takeIf { it.isNotBlank() && it.lines().any { line ->
                    line.startsWith("[") && line.contains(":")
                } }
                if (usable != null) Result.success(usable)
                else Result.failure(NoLyricsFromProvider("KuGou returned no timed lines"))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
