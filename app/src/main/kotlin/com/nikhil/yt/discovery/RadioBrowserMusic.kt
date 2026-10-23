package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.innertube.YouTube
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

/**
 * Radio Browser directory, fully distinct from Openverse, Audius, and YouTube.
 * These are live radio streams, NOT on-demand tracks, so downloads are disabled.
 */
internal object RadioBrowserMusic {
    private val hosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )
    private val uuid = Regex("[0-9a-fA-F-]{36}")
    fun browse(kind: ExperimentalCatalog.HomeLaneKind, genre: String? = null):
        List<ExperimentalCatalog.Track> {
        val tag = when (kind) {
            ExperimentalCatalog.HomeLaneKind.TRENDING -> "music"
            ExperimentalCatalog.HomeLaneKind.NEW -> "indie"
            ExperimentalCatalog.HomeLaneKind.DISCOVER -> genre ?: "ambient"
            ExperimentalCatalog.HomeLaneKind.FOR_YOU -> "jazz"
        }
        val sort = if (kind == ExperimentalCatalog.HomeLaneKind.NEW) "clicktrend" else "votes"
        return query("tag=${Uri.encode(tag.take(30))}&order=$sort&reverse=true&limit=35" +
            "&hidebroken=true&is_https=true")
    }
    fun search(query: String): List<ExperimentalCatalog.Track> =
        query("name=${Uri.encode(query.take(60))}&order=votes&reverse=true" +
            "&limit=30&hidebroken=true&is_https=true")

    private fun query(params: String): List<ExperimentalCatalog.Track> {
        val rows = hosts.firstNotNullOfOrNull { host ->
            try { download("$host/json/stations/search?$params") }
            catch (e: Exception) {
                Timber.tag("CapsuleRadio").d(e, "Radio directory mirror unavailable")
                null
            }
        } ?: return emptyList()
        val tracks = mutableListOf<ExperimentalCatalog.Track>()
        for (i in 0 until rows.length()) {
            val s = rows.optJSONObject(i) ?: continue
            val id = s.optString("stationuuid")
            if (!uuid.matches(id) || s.optInt("lastcheckok", 0) != 1) continue
            val codec = s.optString("codec").lowercase()
            if (codec !in setOf("mp3", "aac", "aac+") || s.optInt("hls", 0) != 0) continue
            if (s.optInt("bitrate", 0) !in 64..400) continue
            val stream = s.optString("url_resolved").takeIf {
                it.startsWith("https://") && it.length < 1000 &&
                    !it.endsWith(".pls", true) && !it.endsWith(".m3u", true)
            } ?: continue
            val title = s.optString("name").trim().replace(Regex("\\s+"), " ").take(80)
            if (title.length < 3) continue
            val tags = s.optString("tags").split(',').asSequence().map(String::trim)
                .filter(String::isNotBlank).take(2).joinToString(" · ")
            val homepage = s.optString("homepage").takeIf { it.startsWith("https://") }
                ?: "https://www.radio-browser.info/"
            val icon = s.optString("favicon").takeIf {
                it.startsWith("https://") && it.length <= 500
            }
            tracks.add(ExperimentalCatalog.Track(
                source = ExperimentalCatalog.Source.RADIO,
                id = id,
                title = title,
                artist = "LIVE · " + tags.ifEmpty { "Radio" },
                artwork = icon,
                artworkLarge = icon,
                streamUrl = stream,
                sourcePage = homepage,
                durationSeconds = 0,
                license = null,
                downloadable = false,
                genre = tags,
                catalogProvider = "radio",
                audioCategory = "music",
            ))
            if (tracks.size >= 20) break
        }
        return tracks.distinctBy { it.id }
    }
    private fun download(url: String): JSONArray? {
        val target = URL(url)
        val proxy = YouTube.streamProxy
        val connection = (if (proxy == null) target.openConnection()
            else target.openConnection(proxy)) as HttpURLConnection
        connection.connectTimeout = 3500
        connection.readTimeout = 4500
        connection.setRequestProperty("User-Agent",
            "CapsuleMusic/1.0 (+https://github.com/vanimusic6-sudo/capsule-MUSIC)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { input ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (sb.length + n > 750_000) return null
                    sb.append(buf, 0, n)
                }
                return JSONArray(sb.toString())
            }
        } finally { connection.disconnect() }
    }
}
