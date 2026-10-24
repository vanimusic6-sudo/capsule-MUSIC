package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

/**
 * Independent Creative Commons remix/song community. Unlike Jamendo and
 * Freesound this is NOT a second Openverse search skin.
 *
 * Official anonymous ccHost query API: https://ccmixter.org/query-api .
 * Fail closed for playback/download when a creator's direct MP3, license or
 * URL cannot be established. Keep useful source-page links discoverable.
 */
internal object CCMixterMusic {
    fun browse(kind: ExperimentalCatalog.HomeLaneKind, tag: String? = null) = fetch(
        when (kind) {
            ExperimentalCatalog.HomeLaneKind.NEW -> "sort=date&ord=desc"
            ExperimentalCatalog.HomeLaneKind.DISCOVER ->
                "sort=num_scores&tags=${Uri.encode(tag?.take(35) ?: "instrumental")}"
            ExperimentalCatalog.HomeLaneKind.FOR_YOU -> "sort=num_scores"
            ExperimentalCatalog.HomeLaneKind.TRENDING -> "sort=num_scores&ord=desc"
        }
    )

    fun search(query: String) = fetch("search=${Uri.encode(query.take(75))}&search_type=any")

    fun byArtist(handleOrName: String): List<ExperimentalCatalog.Track> {
        val name = handleOrName.take(60)
        if (name.matches(Regex("[a-zA-Z0-9_.-]+"))) {
            val byHandle = fetch("u=${Uri.encode(name)}&sort=date")
            if (byHandle.isNotEmpty()) return byHandle
        }
        return search(name).filter { it.artist.contains(name, ignoreCase = true) }
    }

    private fun fetch(parameters: String): List<ExperimentalCatalog.Track> {
        val url = "https://ccmixter.org/api/query?f=json&dataview=info&limit=18&" + parameters
        val data = try { request(url) } catch (error: Exception) {
            Timber.tag("CapsuleCCMixter").d(error, "ccMixter query unavailable")
            return emptyList()
        }
        val rows = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("results")
                ?: data.optJSONArray("items")
                ?: data.optJSONArray("uploads")
                ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until rows.length()).mapNotNull { idx ->
            val obj = rows.optJSONObject(idx) ?: return@mapNotNull null
            val id = obj.optString("upload_id").trim().takeIf {
                it.matches(Regex("[0-9]{1,14}"))
            } ?: return@mapNotNull null
            val title = obj.optString("upload_name").trim().ifBlank {
                obj.optString("name").trim()
            }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = obj.optString("user_real_name").trim().ifBlank {
                obj.optString("user_name").trim()
            }.ifBlank { "ccMixter artist" }
            val handle = obj.optString("user_name").trim()
            val sourcePage = obj.optString("file_page_url").takeIf {
                it.startsWith("https://ccmixter.org/") ||
                    it.startsWith("https://www.ccmixter.org/")
            } ?: "https://ccmixter.org/files/${Uri.encode(handle)}/$id"
            val license = sequenceOf("license_url", "upload_license_url",
                "upload_license_link").map { obj.optString(it) }.firstOrNull {
                it.startsWith("https://creativecommons.org/") ||
                    it.startsWith("http://creativecommons.org/")
            }
            val uploads = obj.optJSONArray("files")
                ?: obj.optJSONArray("upload_files") ?: JSONArray()
            val firstAudio = (0 until uploads.length()).mapNotNull {
                uploads.optJSONObject(it)
            }.firstOrNull { file ->
                val url = file.optString("filedownload_url").ifBlank {
                    file.optString("download_url")
                }.ifBlank { file.optString("file_url") }
                isSafeStream(url)
            }
            val stream = firstAudio?.let { file ->
                sequenceOf("filedownload_url", "download_url", "file_url")
                    .map { file.optString(it) }.firstOrNull(::isSafeStream)
            }?.takeIf { license != null }
            val artwork = obj.optString("user_avatar_url")
                .takeIf { it.startsWith("https://") }
            ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.CCMIXTER,
                id = id,
                title = title,
                artist = author,
                artwork = artwork,
                artworkLarge = artwork,
                streamUrl = stream,
                sourcePage = sourcePage,
                license = license,
                genre = obj.optString("upload_tags").take(110).ifBlank { null },
                artistId = handle.takeIf { it.isNotBlank() },
                artistPage = "https://ccmixter.org/people/${Uri.encode(handle)}",
                catalogProvider = "ccmixter",
                audioCategory = "music",
                // ccMixter rating is a community signal, not a play-count.
                favoriteCount = obj.optLong("upload_num_scores").coerceAtLeast(0L),
            )
        }
    }

    private fun isSafeStream(value: String): Boolean {
        if (!value.startsWith("https://")) return false
        return try {
            val url = URL(value)
            val host = url.host.lowercase()
            (host == "ccmixter.org" || host.endsWith(".ccmixter.org")) &&
                (url.path.endsWith(".mp3", true) || url.path.endsWith(".ogg", true) ||
                    url.path.endsWith(".m4a", true) || url.path.contains("/content/"))
        } catch (_: Exception) { false }
    }

    private fun request(address: String): Any {
        val proxy = YouTube.streamProxy
        val connection = (if (proxy == null) URL(address).openConnection()
            else URL(address).openConnection(proxy)) as HttpURLConnection
        connection.connectTimeout = 5500
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "CapsuleMusic/1.0")
        try {
            if (connection.responseCode !in 200..299) return JSONArray()
            val builder = StringBuilder()
            connection.inputStream.bufferedReader().use {
                val chars = CharArray(4096)
                while (true) {
                    val n = it.read(chars)
                    if (n < 0) break
                    if (builder.length + n > 1_500_000) return JSONArray()
                    builder.append(chars, 0, n)
                }
            }
            val raw = builder.toString().trimStart()
            return when {
                raw.startsWith("[") -> JSONArray(raw)
                raw.startsWith("{") -> JSONObject(raw)
                else -> JSONArray()
            }
        } finally { connection.disconnect() }
    }
}
