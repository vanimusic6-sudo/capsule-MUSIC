/*
 * Capsule Music • Apple Motion Artwork (optional, experimental)
 * GPL-3.0. Apple catalog editorial-video response structure informed by
 * vivizzz007/vivi-music (GPL-3.0); this implementation is independent.
 * No Apple tokens, assets or video data are embedded in the APK.
 */
package com.nikhil.yt.ui.motionart

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Optional unauthenticated Apple web-catalog motion source.
 *
 * Apple's editorial-video URL is not a stable public API: this provider may
 * return null after catalog or web-token changes. Always retain the Capsule
 * palette as an immediate, zero-network fallback. Never call from playback.
 */
internal object AppleMotionArtworkProvider {
    private const val AMP = "https://amp-api.music.apple.com/v1/catalog/us"
    private const val WEB = "https://music.apple.com"
    // Editorial playlist URLs may be signed and expire sooner than metadata.
    private const val SUCCESS_TTL_MS = 30 * 60 * 1000L
    private const val FAILURE_TTL_MS = 20 * 60 * 1000L
    private const val TOKEN_RETRY_MS = 20 * 60 * 1000L
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val mutex = Mutex()
    private var token: String? = null
    private var tokenExpiresMs = 0L
    private var retryTokenAtMs = 0L
    private data class Cached(val url: String?, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Cached>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) =
            size > 48
    }

    suspend fun artist(name: String): String? = find("artists", name, null)
    suspend fun album(title: String, artist: String): String? = find("albums", title, artist)

    private suspend fun find(type: String, title: String, performer: String?): String? {
        if (title.isBlank() || (performer != null && performer.isBlank())) return null
        val key = "$type|${normalize(title)}|${normalize(performer.orEmpty())}"
        return mutex.withLock {
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { it.expiresAt > now }?.let { return@withLock it.url }
            val result = try {
                withContext(Dispatchers.IO) {
                    fetchVideo(type, title, performer)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            cache[key] = Cached(result, now + if (result == null) FAILURE_TTL_MS else SUCCESS_TTL_MS)
            result
        }
    }

    private fun normalize(raw: String) = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")

    private fun fetchVideo(type: String, title: String, performer: String?): String? {
        val jwt = getWebToken() ?: return null
        val query = listOfNotNull(performer, title).joinToString(" ")
        val search = fetchJson("$AMP/search?term=${encode(query)}&types=$type&limit=8", jwt) ?: return null
        val candidates = search.optJSONObject("results")?.optJSONObject(type)
            ?.optJSONArray("data") ?: return null
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val attrs = item.optJSONObject("attributes") ?: continue
            if (normalize(attrs.optString("name")) != normalize(title)) continue
            if (performer != null && normalize(attrs.optString("artistName")) != normalize(performer)) continue
            val id = item.optString("id").takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: continue
            val detail = fetchJson("$AMP/$type/$id?extend=editorialVideo,editorialArtwork", jwt)
                ?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: continue
            extractUrl(detail, if (type == "artists") "motionDetailTall" else "motionDetailSquare")?.let {
                return it
            }
        }
        return null
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun fetchJson(url: String, jwt: String): JSONObject? {
        if (!url.startsWith("$AMP/")) return null
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $jwt")
            .header("Origin", WEB)
            .header("Referer", "$WEB/")
            .header("User-Agent", "Mozilla/5.0 CapsuleMusic/1.0")
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.let(::JSONObject)
        }
    }

    /**
     * Do not ship somebody else's embedded token. A token is discovered only
     * from Apple's public web player's entry bundle. Failure is nonfatal.
     */
    private fun getWebToken(): String? {
        val now = System.currentTimeMillis()
        if (token != null && tokenExpiresMs > now + 60_000L) return token
        if (retryTokenAtMs > now) return null
        retryTokenAtMs = now + TOKEN_RETRY_MS
        val req = Request.Builder().url("$WEB/us/browse")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val html = client.newCall(req).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string() ?: return null
        }
        val scripts = Regex("""(?:/assets/)[\w~.-]*index[\w~.-]*\.js""")
            .findAll(html).map { it.value }.distinct().take(3).toList()
        for (path in scripts) {
            val script = client.newCall(Request.Builder().url("$WEB$path").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: continue
            val candidates = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
                .findAll(script).take(30)
            for (candidate in candidates) {
                val value = candidate.value
                val expiry = try {
                    val payload = String(Base64.decode(value.split(".")[1], Base64.URL_SAFE or Base64.NO_WRAP))
                    JSONObject(payload).optLong("exp") * 1000L
                } catch (_: Exception) { 0L }
                if (expiry > now + 60_000L) {
                    token = value
                    tokenExpiresMs = expiry
                    return value
                }
            }
        }
        return null
    }

    /** Source metadata is untrusted: accept only secure motion video URLs. */
    internal fun extractUrl(attributes: JSONObject, preferred: String): String? {
        val editorial = attributes.optJSONObject("editorialVideo")
            ?: attributes.optJSONObject("editorialArtwork") ?: return null
        val order = listOf(preferred, "motionDetailSquare", "motionDetailTall", "motionDetailRaw")
        for (key in order.distinct()) {
            val value = editorial.optJSONObject(key)?.optString("video").orEmpty()
            if (validVideoUrl(value)) return value
        }
        return null
    }

    internal fun validVideoUrl(value: String): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        // Apple's motion assets are commonly on mzstatic CDN rather than apple.com.
        val appleHost = host == "apple.com" || host.endsWith(".apple.com") ||
            host == "mzstatic.com" || host.endsWith(".mzstatic.com")
        return uri.scheme == "https" && appleHost && uri.userInfo == null &&
            (uri.path.orEmpty().endsWith(".m3u8") || uri.path.orEmpty().endsWith(".mp4"))
    }
}
