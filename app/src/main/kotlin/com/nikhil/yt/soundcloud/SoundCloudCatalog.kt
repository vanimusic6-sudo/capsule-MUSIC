package com.nikhil.yt.soundcloud

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Public SoundCloud catalogue. Call from Dispatchers.IO with an authorised app OAuth token. */
internal object SoundCloudCatalog {
    data class Track(
        val urn: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val permalink: String,
        val access: String,
    )
    sealed interface Result {
        data class Tracks(val items: List<Track>) : Result
        data object MissingToken : Result
        data object Unauthorized : Result
        data object RateLimited : Result
        data object Unavailable : Result
    }

    fun search(query: String, token: String): Result {
        if (token.isBlank()) return Result.MissingToken
        val q = query.trim().take(180)
        if (q.isBlank()) return Result.Tracks(emptyList())
        val requestUrl = "https://api.soundcloud.com/tracks?q=" +
            URLEncoder.encode(q, "UTF-8") + "&access=playable&limit=12&linked_partitioning=true"
        val connection = runCatching {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "OAuth " + token.trim())
            }
        }.getOrNull() ?: return Result.Unavailable
        return try {
            when (connection.responseCode) {
                401, 403 -> Result.Unauthorized
                429 -> Result.RateLimited
                200 -> {
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = JSONObject(text)
                    Result.Tracks(parseCollection(root.optJSONArray("collection") ?: JSONArray()))
                }
                else -> Result.Unavailable
            }
        } catch (_: Exception) {
            Result.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseCollection(collection: JSONArray): List<Track> =
        (0 until minOf(collection.length(), 50)).mapNotNull { index ->
            val item = collection.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("urn").ifBlank {
                item.optString("id").takeIf { it.isNotBlank() }?.let { "soundcloud:tracks:$it" }.orEmpty()
            }
            val title = item.optString("title").trim()
            val owner = item.optJSONObject("user")?.optString("username").orEmpty().trim()
            val permalink = item.optString("permalink_url")
            if (!id.startsWith("soundcloud:tracks:") || title.isBlank() || owner.isBlank() ||
                !permalink.startsWith("https://soundcloud.com/")
            ) return@mapNotNull null
            Track(
                urn = id,
                title = title,
                artist = owner,
                artworkUrl = item.optString("artwork_url").takeIf { it.startsWith("https://") },
                permalink = permalink,
                access = item.optString("access"),
            )
        }.distinctBy { it.urn }
}
