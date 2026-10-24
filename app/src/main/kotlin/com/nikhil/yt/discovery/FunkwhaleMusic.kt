package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A read-only independent source. open.audio is a community Funkwhale pod,
 * not a universal federated search service. Anonymous access may be limited by
 * pod policy, network route, or upstream outages.
 *
 * Funkwhale 2.0 changed its wire schema: v2 uses GUID/name/artistCredit,
 * whereas v1 uses numeric id/title/artist. Do not mistake absent uploads for
 * playable streams or silently manufacture media URLs.
 */
internal object FunkwhaleMusic {
    private const val POD = "https://open.audio"
    private const val FAILURE_COOLDOWN_MS = 15_000L
    private const val MAX_RESPONSE_CHARS = 750_000
    private val guid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val legacyId = Regex("[0-9]{1,12}")

    @Volatile private var failedUntil = 0L
    @Volatile private var lastPublicIndex: List<ExperimentalCatalog.Track> = emptyList()
    @Volatile private var lastPublicIndexTime = 0L

    fun browse(kind: ExperimentalCatalog.HomeLaneKind, taste: String? = null):
        List<ExperimentalCatalog.Track> {
        val recent = publicIndex()
        if (kind != ExperimentalCatalog.HomeLaneKind.FOR_YOU || taste.isNullOrBlank()) {
            return when (kind) {
                ExperimentalCatalog.HomeLaneKind.DISCOVER ->
                    recent.sortedWith(compareByDescending<ExperimentalCatalog.Track> { it.playable }
                        .thenBy { it.title })
                else -> recent
            }
        }
        // Even if search is restricted, retain the open public index as a
        // discoverable catalog, rather than rendering an empty page.
        return runCatching { fetch(taste.take(90)) }.getOrDefault(emptyList())
            .ifEmpty { recent }
    }

    fun search(query: String): List<ExperimentalCatalog.Track> =
        runCatching { fetch(query.take(90)) }.getOrElse { e ->
            Timber.tag("Funkwhale").w(e, "Public catalog search unavailable")
            emptyList()
        }

    fun artist(id: String?, name: String): List<ExperimentalCatalog.Track> =
        search(name).filter { track ->
            if (!id.isNullOrBlank()) track.artistId == id
            else track.artist.equals(name, ignoreCase = true)
        }

    private fun publicIndex(): List<ExperimentalCatalog.Track> {
        val now = System.currentTimeMillis()
        if (lastPublicIndex.isNotEmpty() && now - lastPublicIndexTime < 5 * 60_000L)
            return lastPublicIndex
        if (now < failedUntil) return lastPublicIndex
        return try {
            val results = fetch(null)
            if (results.isNotEmpty()) {
                lastPublicIndex = results
                lastPublicIndexTime = now
                failedUntil = 0L
            } else failedUntil = now + FAILURE_COOLDOWN_MS
            results.ifEmpty { lastPublicIndex }
        } catch (error: Exception) {
            failedUntil = now + FAILURE_COOLDOWN_MS
            Timber.tag("Funkwhale").w(error, "Public Funkwhale pod unavailable")
            lastPublicIndex
        }
    }

    private fun fetch(query: String?): List<ExperimentalCatalog.Track> {
        // The 2.0 release dropped v1. Only use the published v2 endpoint;
        // retrying v1 on a v2-only pod adds latency without helping.
        val address = buildString {
            append(POD)
            append("/api/v2/tracks/?page_size=30&ordering=-creation_date")
            if (!query.isNullOrBlank()) append("&q=" + Uri.encode(query))
        }
        val url = URL(address)
        val proxy = YouTube.streamProxy
        val connection = (if (proxy == null) url.openConnection() else url.openConnection(proxy))
            as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "CapsuleMusic/1.1")
        try {
            if (connection.responseCode != 200) {
                Timber.tag("Funkwhale").w("Public catalog status=%d", connection.responseCode)
                return emptyList()
            }
            if (!connection.contentType.orEmpty().lowercase().contains("json")) return emptyList()
            if (connection.contentLengthLong > 1_500_000L) return emptyList()
            val body = buildString {
                connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var remaining = MAX_RESPONSE_CHARS
                    while (remaining > 0) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        append(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            return parse(JSONObject(body).optJSONArray("results") ?: return emptyList())
        } finally {
            connection.disconnect()
        }
    }

    /** Supports real Funkwhale v2 names/GUIDs and v1 metadata from legacy nodes. */
    internal fun parse(rows: JSONArray): List<ExperimentalCatalog.Track> =
        (0 until minOf(rows.length(), 40)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (!row.optBoolean("playable", row.optBoolean("is_playable", true)))
                return@mapNotNull null
            val id = row.optString("guid").takeIf(guid::matches)
                ?: row.optString("id").takeIf { guid.matches(it) || legacyId.matches(it) }
                ?: return@mapNotNull null
            val title = (row.optString("name").takeIf(String::isNotBlank)
                ?: row.optString("title")).trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val release = row.optJSONObject("release") ?: row.optJSONObject("album")
            val artistObject = row.optJSONObject("artist") ?: release?.optJSONObject("artist")
            val credits = row.optJSONArray("artistCredit")
                ?: release?.optJSONArray("artistCredit")
            val firstCredit = credits?.optJSONObject(0)
            val artist = firstCredit?.optString("name")?.takeIf(String::isNotBlank)
                ?: artistObject?.optString("name")?.takeIf(String::isNotBlank)
                ?: "Unknown artist"
            val artistId = firstCredit?.optString("guid")?.takeIf(guid::matches)
                ?: artistObject?.optString("guid")?.takeIf(guid::matches)
                ?: artistObject?.optString("id")?.takeIf(String::isNotBlank)
            val covers = row.optJSONObject("cover") ?: release?.optJSONObject("cover")
            val urls = covers?.optJSONObject("urls")
            val art = listOf("medium_square_crop", "large_square_crop", "original", "source")
                .firstNotNullOfOrNull { k ->
                    urls?.optString(k)?.takeIf { it.startsWith("https://") }
                } ?: covers?.optString("url")?.takeIf { it.startsWith("https://") }

            val uploads = row.optJSONArray("uploads")
            val upload = (0 until (uploads?.length() ?: 0)).firstNotNullOfOrNull { j ->
                uploads?.optJSONObject(j)?.let { obj ->
                    obj.optString("uuid").takeIf(guid::matches)
                        ?: obj.optString("guid").takeIf(guid::matches)
                }
            }
            // /listen/{uuid}/ accepts UPLOAD uuid, not track id. Missing upload
            // means a publisher page, not a fake stream link.
            val api = if (legacyId.matches(id)) "v1" else "v2"
            ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.FUNKWHALE,
                id = "open-audio:$id",
                title = title,
                artist = artist,
                artistId = artistId,
                artwork = art,
                artworkLarge = urls?.optString("original")?.takeIf { it.startsWith("https://") },
                streamUrl = upload?.let { "$POD/api/$api/listen/$it/" },
                sourcePage = "$POD/library/tracks/$id",
                durationSeconds = row.optInt("duration", 0),
                audioCategory = "music",
                catalogProvider = "funkwhale",
                downloadable = false,
            )
        }
}
