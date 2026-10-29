package com.nikhil.yt.ui.screens.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A verified individual, not a disbanded band or an unconfirmed name match. */
data class ArtistMemorialInfo(
    val deathDate: String,
    val sourceUrl: String,
)

/**
 * Optional artist-page enhancement. Unknown, unavailable, ambiguous or living -> no candle.
 *
 * MusicBrainz requires a meaningful User-Agent and <= 1 request/second per IP.
 * One request is made only when a remote artist page resolves, never from scrolling.
 * Local bounded TTL cache avoids repeated lookups while browsing.
 */
internal object ArtistMemorialLookup {
    private const val POSITIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 2L * 60 * 60 * 1000
    private const val MIN_REQUEST_INTERVAL_MS = 1_150L
    private const val MAX_CACHE_ENTRIES = 96
    private val requestMutex = Mutex()
    private data class CacheEntry(val result: ArtistMemorialInfo?, val expiresAt: Long)
    private val cache = LinkedHashMap<String, CacheEntry>()
    private var lastRequestAt = 0L
    private val mbidRegex = Regex("^[a-fA-F0-9-]{36}$")
    private val dateRegex = Regex("^\\d{4}(-\\d{2}){0,2}$")

    suspend fun find(name: String): ArtistMemorialInfo? {
        val cleanName = name.trim()
        if (cleanName.length !in 2..90 || cleanName.any { it.isISOControl() }) return null
        val key = cleanName.lowercase(Locale.ROOT)
        cached(key)?.let { return it.result }

        return requestMutex.withLock {
            // Recheck after waiting: another caller may have populated the entry.
            cached(key)?.let { return@withLock it.result }
            val elapsed = System.currentTimeMillis() - lastRequestAt
            if (elapsed in 0 until MIN_REQUEST_INTERVAL_MS) {
                delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
            lastRequestAt = System.currentTimeMillis()
            val info = try {
                withContext(Dispatchers.IO) { request(cleanName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Memorial status is optional; a slow/offline metadata service must
                // never interrupt artist loading, playback or navigation.
                null
            }
            synchronized(cache) {
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    cache.remove(cache.keys.first())
                }
                cache[key] = CacheEntry(
                    result = info,
                    expiresAt = System.currentTimeMillis() +
                        if (info != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS,
                )
            }
            info
        }
    }

    private fun cached(key: String): CacheEntry? = synchronized(cache) {
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun request(name: String): ArtistMemorialInfo? {
        val query = "artist:" + '"' + name.replace('"', ' ').trim() + '"' + " AND type:person"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://musicbrainz.org/ws/2/artist/?query=$encodedQuery&fmt=json&limit=8")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "CapsuleMusic/1.1.2 (https://github.com/vanimusic6-sudo/capsule-MUSIC)",
            )
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                // A fixed, small response limit prevents unexpected RAM allocation.
                it.readText().take(96_000)
            }
            parseDeath(response, name)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A name alone is not sufficient if multiple people share it.
     * Restrict to one exact high-score Person with a recorded death date.
     * Unknown != alive; on uncertainty hide the button rather than mislabel.
     */
    internal fun parseDeath(json: String, requestedName: String): ArtistMemorialInfo? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return null
        val artists = runCatching { root["artists"]?.jsonArray }.getOrNull() ?: return null
        val matches = artists.mapNotNull { it as? JsonObject }.filter { candidate ->
            candidate["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.equals(requestedName.trim(), ignoreCase = true) == true &&
                candidate["type"]?.jsonPrimitive?.contentOrNull == "Person" &&
                (candidate["score"]?.jsonPrimitive?.intOrNull ?: 0) >= 95
        }
        if (matches.size != 1) return null
        val candidate = matches.single()
        val life = candidate["life-span"] as? JsonObject ?: return null
        if (life["ended"]?.jsonPrimitive?.booleanOrNull != true) return null
        val endDate = life["end"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!dateRegex.matches(endDate)) return null
        val id = candidate["id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!mbidRegex.matches(id)) return null
        return ArtistMemorialInfo(
            deathDate = endDate,
            sourceUrl = "https://musicbrainz.org/artist/$id",
        )
    }
}
