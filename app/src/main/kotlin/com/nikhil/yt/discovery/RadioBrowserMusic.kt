package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.R
import com.nikhil.yt.innertube.YouTube
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

/**
 * Radio Browser community directory. A station is a live broadcast, NOT a
 * downloadable Audius/YouTube track. Categories are explicit country/tag
 * searches, verified against returned metadata, never random broadcasts.
 */
internal object RadioBrowserMusic {
    enum class Category(val titleRes: Int, val country: String?, val tags: List<String>,
                        val exclude: List<String> = emptyList()) {
        RUSSIAN(R.string.capsule_radio_russian, "RU",
            listOf("music","pop","rock","dance","hits","русская","музыка"),
            listOf("talk","news","religious","politics","speech")),
        AMERICAN(R.string.capsule_radio_american, "US",
            listOf("music","pop","rock","country","rnb","jazz","hip hop","hits"),
            listOf("talk","news","religious","politics","speech")),
        MUSIC(R.string.capsule_radio_music, null,
            listOf("music","pop","rock","jazz","classical","dance","indie","electronic","hits"),
            listOf("talk","news","politics","speech","scanner")),
        NEWS(R.string.capsule_radio_news, null,
            listOf("news","politics","political","talk","current affairs","новости","информация"),
            listOf("music only","meditation","sleep")),
        KIDS(R.string.capsule_radio_kids, null,
            listOf("kids","children","childrens","family","детское","дети","детская","kinderradio"),
            listOf("adult","18+")),
        JAZZ(R.string.capsule_radio_jazz, null,
            listOf("jazz","smooth jazz","bebop","swing"),
            listOf("news","talk","politics")),
        ROCK(R.string.capsule_radio_rock, null,
            listOf("rock","alternative rock","classic rock","metal","indie rock"),
            listOf("news","talk","politics")),
        ELECTRONIC(R.string.capsule_radio_electronic, null,
            listOf("electronic","techno","house","trance","ambient","dance","edm"),
            listOf("news","talk","politics")),
    }
    val categories = Category.entries.toList()
    private val hosts = listOf("https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info","https://at1.api.radio-browser.info")
    private val uuid = Regex("[0-9a-fA-F-]{36}")
    private val supported = setOf("mp3","aac","aac+")

    fun browse(category: Category): List<ExperimentalCatalog.Track> {
        // Country categories must be country-filtered on the SERVER and locally.
        // For genre shelves ask for a precise tag, not an arbitrary top-voted
        // station. More than one query is used only when initial results are weak.
        val tagQueries = when (category) {
            Category.RUSSIAN, Category.AMERICAN -> listOf(null as String?)
            Category.MUSIC -> listOf("music","pop")
            Category.NEWS -> listOf("news","talk")
            Category.KIDS -> listOf("kids","children","детское")
            Category.JAZZ -> listOf("jazz")
            Category.ROCK -> listOf("rock")
            Category.ELECTRONIC -> listOf("electronic","house")
        }
        val output = linkedMapOf<String, ExperimentalCatalog.Track>()
        for (tag in tagQueries) {
            val args = buildList {
                if (category.country != null) add("countrycode=${category.country}")
                if (tag != null) add("tag=${Uri.encode(tag)}")
                add("order=votes")
                add("reverse=true")
                add("limit=65")
                add("hidebroken=true")
                add("is_https=true")
            }.joinToString("&")
            val rows = fetchRows(args)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (!matches(row, category)) continue
                parse(row)?.let { output.putIfAbsent(it.id, it) }
                if (output.size >= 15) break
            }
            if (output.size >= 9) break
        }
        return output.values.take(15)
    }

    fun browse(kind: ExperimentalCatalog.HomeLaneKind, genre: String? = null):
        List<ExperimentalCatalog.Track> = browse(
        when (kind) {
            ExperimentalCatalog.HomeLaneKind.NEW -> Category.ROCK
            ExperimentalCatalog.HomeLaneKind.TRENDING -> Category.MUSIC
            ExperimentalCatalog.HomeLaneKind.DISCOVER -> when (genre?.lowercase()) {
                "jazz" -> Category.JAZZ
                "rock" -> Category.ROCK
                else -> Category.ELECTRONIC
            }
            ExperimentalCatalog.HomeLaneKind.FOR_YOU -> Category.JAZZ
        }
    )

    fun search(query: String): List<ExperimentalCatalog.Track> {
        val q = query.trim().take(50)
        if (q.length < 2) return emptyList()
        val rows = fetchRows("name=${Uri.encode(q)}&order=votes&reverse=true&limit=45" +
            "&hidebroken=true&is_https=true")
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parse) }.take(20)
    }

    private fun matches(row: JSONObject, category: Category): Boolean {
        if (row.optInt("lastcheckok") != 1) return false
        if (category.country != null &&
            row.optString("countrycode").uppercase() != category.country) return false
        val tags = row.optString("tags").lowercase().replace('_',' ')
            .split(',').map(String::trim)
        val name = row.optString("name").lowercase()
        val allText = (tags + name).joinToString(" ")
        if (category.exclude.any { bad -> tags.any { it.contains(bad) } }) return false
        // RU/US music stations must actually identify a music genre; neither
        // the country nor a large vote count is evidence of music.
        return category.tags.any { genre ->
            tags.any { it == genre || it.contains(genre) } ||
                (category == Category.KIDS || category == Category.NEWS) &&
                    name.contains(genre)
        } && !(category != Category.NEWS && category != Category.KIDS &&
            listOf("politics","religion","news bulletin","podcast").any(allText::contains))
    }

    private fun parse(row: JSONObject): ExperimentalCatalog.Track? {
        val id = row.optString("stationuuid").takeIf(uuid::matches) ?: return null
        if (row.optInt("lastcheckok") != 1 || row.optInt("hls") != 0) return null
        if (row.optString("codec").lowercase() !in supported ||
            row.optInt("bitrate") !in 64..400) return null
        val audio = row.optString("url_resolved").takeIf {
            it.startsWith("https://") && it.length < 1000 &&
                !it.endsWith(".pls",true) && !it.endsWith(".m3u",true)
        } ?: return null
        val name = row.optString("name").trim().replace(Regex("\\s+")," ").take(75)
        if (name.length < 3) return null
        // Only real broadcaster artwork. No generic scraped or invented covers.
        val icon = row.optString("favicon").takeIf {
            it.startsWith("https://") && it.length < 500 &&
                !it.endsWith(".svg",true)
        } ?: return null
        val homepage = row.optString("homepage").takeIf { it.startsWith("https://") }
            ?: "https://www.radio-browser.info/"
        val tags = row.optString("tags").split(',').map(String::trim)
            .filter(String::isNotBlank).take(3).joinToString(" · ")
        return ExperimentalCatalog.Track(
            source = ExperimentalCatalog.Source.RADIO,
            id = id,
            title = name,
            artist = "LIVE · " + (tags.ifBlank { row.optString("countrycode") }),
            artwork = icon,
            artworkLarge = icon,
            streamUrl = audio,
            sourcePage = homepage,
            durationSeconds = 0,
            license = null,
            downloadable = false,
            genre = row.optString("tags").take(150),
            catalogProvider = "radio",
            audioCategory = "music",
            favoriteCount = row.optLong("votes").coerceAtLeast(0L),
            playCount = row.optLong("clickcount").coerceAtLeast(0L),
        )
    }

    private fun fetchRows(params: String): JSONArray {
        for (host in hosts) {
            try {
                val target = URL("$host/json/stations/search?$params")
                val proxy = YouTube.streamProxy
                val conn = (if (proxy == null) target.openConnection()
                    else target.openConnection(proxy)) as HttpURLConnection
                conn.connectTimeout = 3300
                conn.readTimeout = 4500
                conn.setRequestProperty("User-Agent",
                    "CapsuleMusic/1.0 (+https://github.com/vanimusic6-sudo/capsule-MUSIC)")
                conn.setRequestProperty("Accept","application/json")
                try {
                    if (conn.responseCode !in 200..299) continue
                    val content = StringBuilder()
                    conn.inputStream.bufferedReader().use { reader ->
                        val buff = CharArray(4096)
                        while (true) {
                            val count = reader.read(buff)
                            if (count < 0) break
                            if (content.length + count > 1_300_000) break
                            content.append(buff,0,count)
                        }
                    }
                    return JSONArray(content.toString())
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Timber.tag("CapsuleRadio").d(e,"Station directory mirror unavailable")
            }
        }
        return JSONArray()
    }
}
