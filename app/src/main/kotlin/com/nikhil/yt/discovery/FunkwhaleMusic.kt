package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Anonymous read-only public content from a community Funkwhale pod.
 * Does not access authenticated/restricted libraries or assume download rights.
 */
internal object FunkwhaleMusic {
    private const val POD = "https://open.audio"
    private val safeId = Regex("[a-zA-Z0-9_-]{1,90}")
    private val safeUpload = Regex("[a-fA-F0-9-]{36}")

    fun browse(kind: ExperimentalCatalog.HomeLaneKind, taste: String? = null):
        List<ExperimentalCatalog.Track> = when (kind) {
            ExperimentalCatalog.HomeLaneKind.FOR_YOU -> request(taste?.takeIf(String::isNotBlank) ?: "music")
            ExperimentalCatalog.HomeLaneKind.DISCOVER -> request("instrumental")
            else -> request(null)
        }

    fun search(query: String): List<ExperimentalCatalog.Track> = request(query.take(90))

    fun artist(id: String?, name: String): List<ExperimentalCatalog.Track> =
        request(name.take(90)).filter { track ->
            if (!id.isNullOrBlank()) track.artistId == id
            else track.artist.equals(name, ignoreCase = true)
        }

    private fun request(query: String?): List<ExperimentalCatalog.Track> {
        val address = buildString {
            append(POD)
            append("/api/v2/tracks/?page_size=35&ordering=-creation_date")
            if (!query.isNullOrBlank()) append("&q=" + Uri.encode(query))
        }
        val proxy = YouTube.streamProxy
        val url = URL(address)
        val connection = (if (proxy == null) url.openConnection() else url.openConnection(proxy))
            as HttpURLConnection
        connection.connectTimeout = 9_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "CapsuleMusic/1.0")
        try {
            if (connection.responseCode != 200) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText().take(750_000) }
            return parse(JSONObject(body).optJSONArray("results") ?: return emptyList())
        } finally { connection.disconnect() }
    }

    internal fun parse(rows: JSONArray): List<ExperimentalCatalog.Track> =
        (0 until minOf(rows.length(), 40)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (!row.optBoolean("is_playable", true)) return@mapNotNull null
            val id = (row.optString("id").takeIf { safeId.matches(it) }
                ?: row.optString("guid").takeIf { safeId.matches(it) })
                ?: return@mapNotNull null
            val album = row.optJSONObject("album")
            val artist = row.optJSONObject("artist") ?: album?.optJSONObject("artist")
            val name = artist?.optString("name").orEmpty().ifBlank { "Funkwhale" }
            val title = row.optString("title").trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            // Public upload UUID, not a guessed track or library identifier.
            val uploads = row.optJSONArray("uploads")
            val upload = (0 until (uploads?.length() ?: 0)).mapNotNull { j ->
                val item = uploads?.optJSONObject(j) ?: return@mapNotNull null
                item.optString("uuid").takeIf(safeUpload::matches)
                    ?: item.optString("guid").takeIf(safeUpload::matches)
            }.firstOrNull()
            val cover = album?.optJSONObject("cover") ?: row.optJSONObject("cover")
            val urls = cover?.optJSONObject("urls")
            val artwork = listOf("medium_square_crop", "large_square_crop", "original", "source")
                .firstNotNullOfOrNull { key ->
                    urls?.optString(key)?.takeIf { it.startsWith("https://") }
                } ?: cover?.optString("url")?.takeIf { it.startsWith("https://") }
            ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.FUNKWHALE,
                id = "open-audio:$id",
                title = title,
                artist = name,
                artwork = artwork,
                artworkLarge = urls?.optString("original")?.takeIf { it.startsWith("https://") },
                streamUrl = upload?.let { "$POD/api/v2/listen/$it/" },
                sourcePage = "$POD/library/tracks/$id",
                durationSeconds = row.optInt("duration", 0),
                artistId = artist?.optString("id")?.takeIf(safeId::matches),
                audioCategory = "music",
                catalogProvider = "funkwhale",
                downloadable = false,
            )
        }
}
