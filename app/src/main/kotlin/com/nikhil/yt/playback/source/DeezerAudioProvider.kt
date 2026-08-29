
/* * capsule fork
 * Deezer matching + diagnostics adapted from MetroFuse / Metrolist (GPL-3.0).
 *
 * IMPORTANT:
 * - This provider never modifies Capsule's YouTube media id.
 * - It only returns a Deezer stream when the resolver explicitly reports a
 *   clear/direct HTTP(S) media URL.
 * - Protected full streams are reported as PROTECTED and the caller keeps the
 *   already-playing YouTube stream.
 */

package com.nikhil.yt.playback.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object DeezerAudioProvider {
    const val DEFAULT_RESOLVER_URL =
        "https://dzmedia-metrofuse.onrender.com/get_url"

    private const val FALLBACK_RESOLVER_URL =
        "https://yesitworkssomehow-funny-deeza-api-and-yeah.hf.space/get_url"

    private const val SEARCH_API_URL = "https://api.deezer.com/search/track"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val MIN_MATCH_SCORE = 80
    private const val SEARCH_LIMIT = 5
    private const val STREAM_CACHE_MS = 20 * 60 * 1000L
    private const val FAILURE_CACHE_MS = 60 * 1000L
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val resolverUrl: String = DEFAULT_RESOLVER_URL,
        val fastMode: Boolean = true,
        val quality: DeezerAudioQuality = DeezerAudioQuality.MP3_320,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
    )

    sealed interface Resolution {
        data class Direct(val stream: Resolved) : Resolution
        data class Protected(val trackId: String, val cipher: String) : Resolution
        data class Unavailable(val reason: String) : Resolution
    }

    enum class FullStreamState {
        DIRECT,
        PROTECTED,
        UNAVAILABLE,
    }

    data class AccessTest(
        val apiReachable: Boolean,
        val apiLatencyMs: Long?,
        val previewReachable: Boolean,
        val previewLatencyMs: Long?,
        val resolverReachable: Boolean,
        val resolverLatencyMs: Long?,
        val fullStreamState: FullStreamState,
        val detail: String,
    )

    private data class MatchedTrack(
        val trackId: String,
        val title: String,
        val artistNames: List<String>,
        val album: String?,
        val durationMs: Long?,
        val previewUrl: String?,
    )

    private data class CachedResolution(
        val resolution: Resolution,
        val expiresAtMs: Long,
    )

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, CachedResolution>()

    fun invalidate(mediaId: String) {
        val prefix = "$mediaId::"
        streamCache.keys.removeIf { it.startsWith(prefix) }
        trackCache.remove(mediaId)
    }

    fun resolve(query: Query): Resolution {
        val resolvers =
            buildList {
                normalizeResolverUrl(query.resolverUrl)?.let(::add)
                normalizeResolverUrl(DEFAULT_RESOLVER_URL)?.let(::add)
                normalizeResolverUrl(FALLBACK_RESOLVER_URL)?.let(::add)
            }.distinct()

        if (resolvers.isEmpty()) {
            return Resolution.Unavailable("Invalid Deezer resolver URL")
        }

        val track =
            trackCache[query.mediaId]
                ?: findBestTrack(query)?.also { trackCache[query.mediaId] = it }
                ?: return Resolution.Unavailable("Deezer match not found for ${query.title}")

        var lastUnavailable: Resolution.Unavailable? = null
        var protected: Resolution.Protected? = null

        for (resolver in resolvers.take(if (query.fastMode) 2 else resolvers.size)) {
            val cacheKey = "${query.mediaId}::$resolver::${query.fastMode}::${query.quality.name}"
            val now = System.currentTimeMillis()
            streamCache[cacheKey]
                ?.takeIf { it.expiresAtMs > now }
                ?.let { cached ->
                    when (val result = cached.resolution) {
                        is Resolution.Direct -> return result
                        is Resolution.Protected -> protected = protected ?: result
                        is Resolution.Unavailable -> lastUnavailable = result
                    }
                }

            val result = requestFullStream(resolver, track, query)
            val ttl =
                when (result) {
                    is Resolution.Direct ->
                        (result.stream.expiresAtMs - now).coerceAtLeast(60_000L)
                    is Resolution.Protected -> FAILURE_CACHE_MS
                    is Resolution.Unavailable -> FAILURE_CACHE_MS
                }
            streamCache[cacheKey] = CachedResolution(result, now + ttl)

            when (result) {
                is Resolution.Direct -> return result
                is Resolution.Protected -> protected = protected ?: result
                is Resolution.Unavailable -> lastUnavailable = result
            }
        }

        return protected
            ?: lastUnavailable
            ?: Resolution.Unavailable("Deezer resolver did not return a playable stream")
    }

    fun testAccess(resolverUrl: String = DEFAULT_RESOLVER_URL): AccessTest {
        val query =
            Query(
                mediaId = "capsule-source-test",
                title = "One More Time",
                artists = listOf("Daft Punk"),
                album = null,
                durationMs = null,
                resolverUrl = resolverUrl,
                fastMode = true,
                quality = DeezerAudioQuality.MP3_320,
            )

        val apiStarted = System.currentTimeMillis()
        val match = findBestTrack(query)
        val apiLatency = System.currentTimeMillis() - apiStarted

        if (match == null) {
            return AccessTest(
                apiReachable = false,
                apiLatencyMs = apiLatency,
                previewReachable = false,
                previewLatencyMs = null,
                resolverReachable = false,
                resolverLatencyMs = null,
                fullStreamState = FullStreamState.UNAVAILABLE,
                detail = "Deezer search did not return a usable track",
            )
        }

        val (previewOk, previewLatency) = probePreview(match.previewUrl)

        val resolverStarted = System.currentTimeMillis()
        val result = resolve(query)
        val resolverLatency = System.currentTimeMillis() - resolverStarted

        return when (result) {
            is Resolution.Direct ->
                AccessTest(
                    apiReachable = true,
                    apiLatencyMs = apiLatency,
                    previewReachable = previewOk,
                    previewLatencyMs = previewLatency,
                    resolverReachable = true,
                    resolverLatencyMs = resolverLatency,
                    fullStreamState = FullStreamState.DIRECT,
                    detail = "Direct full Deezer stream available (${result.stream.label})",
                )

            is Resolution.Protected ->
                AccessTest(
                    apiReachable = true,
                    apiLatencyMs = apiLatency,
                    previewReachable = previewOk,
                    previewLatencyMs = previewLatency,
                    resolverReachable = true,
                    resolverLatencyMs = resolverLatency,
                    fullStreamState = FullStreamState.PROTECTED,
                    detail = "Search and resolver work; full media uses ${result.cipher}",
                )

            is Resolution.Unavailable ->
                AccessTest(
                    apiReachable = true,
                    apiLatencyMs = apiLatency,
                    previewReachable = previewOk,
                    previewLatencyMs = previewLatency,
                    resolverReachable = false,
                    resolverLatencyMs = resolverLatency,
                    fullStreamState = FullStreamState.UNAVAILABLE,
                    detail = result.reason,
                )
        }
    }

    private fun probePreview(url: String?): Pair<Boolean, Long?> {
        if (url.isNullOrBlank()) return false to null
        val started = System.currentTimeMillis()
        val request =
            Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-2047")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful || response.code == 206
                ok to (System.currentTimeMillis() - started)
            }
        }.getOrElse { false to (System.currentTimeMillis() - started) }
    }

    private fun normalizeResolverUrl(value: String): String? {
        val raw = value.trim().ifBlank { DEFAULT_RESOLVER_URL }
        val parsed = raw.toHttpUrlOrNull() ?: return null
        val cleanPath = parsed.encodedPath.trimEnd('/')
        return if (cleanPath.endsWith("/get_url", ignoreCase = true)) {
            parsed.newBuilder().encodedPath(cleanPath).build().toString()
        } else {
            val path = if (cleanPath.isBlank()) "/get_url" else "$cleanPath/get_url"
            parsed.newBuilder().encodedPath(path).build().toString()
        }
    }

    private fun findBestTrack(query: Query): MatchedTrack? {
        val terms = searchTerms(query)
        for (term in terms.take(if (query.fastMode) 1 else terms.size)) {
            val results = searchTracks(term) ?: continue
            selectBestTrack(results, query)?.let { return it }
        }
        return null
    }

    private fun searchTracks(term: String): JSONArray? {
        val url =
            SEARCH_API_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", term)
                .addQueryParameter("limit", SEARCH_LIMIT.toString())
                .build()
        val request =
            Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                JSONObject(body).optJSONArray("data")
            }
        }.getOrNull()
    }

    private fun selectBestTrack(results: JSONArray, query: Query): MatchedTrack? {
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedDurationMs = query.durationMs

        data class Candidate(val track: MatchedTrack, val score: Int)

        val candidates = mutableListOf<Candidate>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val titleRaw = obj.optString("title")
            val title = titleRaw.titleMatchNormalized()
            val artists = collectArtistNames(obj).map { it.normalized() }
            val album = obj.optJSONObject("album")?.optString("title").normalized()
            val durationMs = obj.optLong("duration", -1L).takeIf { it > 0L }?.times(1000L)

            var score = 0
            score +=
                when {
                    title == wantedTitle && wantedTitle.isNotBlank() -> 120
                    title.contains(wantedTitle) || wantedTitle.contains(title) -> 70
                    else -> 0
                }

            if (wantedArtists.isNotEmpty()) {
                val artistMatch =
                    wantedArtists.any { wanted ->
                        artists.any { candidate ->
                            candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
                        }
                    }
                score += if (artistMatch) 80 else -70
            }

            if (wantedAlbum.isNotBlank() && album.isNotBlank()) {
                score +=
                    when {
                        album == wantedAlbum -> 35
                        album.contains(wantedAlbum) || wantedAlbum.contains(album) -> 18
                        else -> 0
                    }
            }

            if (wantedDurationMs != null && durationMs != null) {
                val diff = abs(wantedDurationMs - durationMs)
                score +=
                    when {
                        diff <= 5_000L -> 45
                        diff <= 20_000L -> 20
                        diff <= 45_000L -> 4
                        else -> -90
                    }
            }

            if (score >= MIN_MATCH_SCORE) {
                candidates +=
                    Candidate(
                        track =
                            MatchedTrack(
                                trackId = id,
                                title = titleRaw,
                                artistNames = collectArtistNames(obj),
                                album = obj.optJSONObject("album")?.optString("title"),
                                durationMs = durationMs,
                                previewUrl = obj.optString("preview").takeIf { it.isNotBlank() },
                            ),
                        score = score,
                    )
            }
        }

        return candidates.maxByOrNull { it.score }?.track
    }

    private fun requestFullStream(
        resolverUrl: String,
        track: MatchedTrack,
        query: Query,
    ): Resolution {
        val targetUrl =
            resolverUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply {
                    if (query.fastMode) addQueryParameter("mode", "fast")
                }
                ?.build()
                ?: return Resolution.Unavailable("Invalid Deezer resolver URL")

        val formats =
            JSONArray().also { array ->
                when (query.quality) {
                    DeezerAudioQuality.FLAC -> {
                        array.put("FLAC")
                        array.put("MP3_320")
                        array.put("MP3_128")
                    }
                    DeezerAudioQuality.MP3_320 -> {
                        array.put("MP3_320")
                        array.put("MP3_128")
                    }
                    DeezerAudioQuality.MP3_128 -> array.put("MP3_128")
                }
            }

        val body =
            JSONObject()
                .put("formats", formats)
                .put("ids", JSONArray().put(track.trackId.toLongOrNull() ?: track.trackId))
                .put("fast", query.fastMode)

        val request =
            Request.Builder()
                .url(targetUrl)
                .post(body.toString().toRequestBody(JSON))
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    return@use Resolution.Unavailable(
                        "Deezer resolver HTTP ${response.code}: ${payload.take(120)}",
                    )
                }
                parseMediaEnvelope(payload, track, query)
            }
        }.getOrElse { error ->
            Resolution.Unavailable(
                "Deezer resolver failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun parseMediaEnvelope(
        payload: String,
        track: MatchedTrack,
        query: Query,
    ): Resolution {
        val root =
            runCatching { JSONObject(payload) }.getOrNull()
                ?: return Resolution.Unavailable("Deezer resolver returned invalid JSON")
        val mediaArray =
            root.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optJSONArray("media")
                ?: return Resolution.Unavailable("Deezer resolver returned no media")

        val wantedFormats =
            when (query.quality) {
                DeezerAudioQuality.FLAC -> listOf("FLAC", "MP3_320", "MP3_128")
                DeezerAudioQuality.MP3_320 -> listOf("MP3_320", "MP3_128")
                DeezerAudioQuality.MP3_128 -> listOf("MP3_128")
            }

        val candidates =
            buildList {
                for (i in 0 until mediaArray.length()) {
                    val candidate = mediaArray.optJSONObject(i) ?: continue
                    if ((candidate.optJSONArray("sources")?.length() ?: 0) > 0) add(candidate)
                }
            }

        val selected =
            wantedFormats.firstNotNullOfOrNull { wanted ->
                candidates.firstOrNull { it.optString("format").equals(wanted, ignoreCase = true) }
            } ?: candidates.firstOrNull()
                ?: return Resolution.Unavailable("Deezer resolver returned no source")

        val cipher = selected.optJSONObject("cipher")?.optString("type").orEmpty().trim()
        if (
            cipher.isNotBlank() &&
            !cipher.equals("none", ignoreCase = true) &&
            !cipher.equals("clear", ignoreCase = true) &&
            !cipher.equals("plain", ignoreCase = true)
        ) {
            return Resolution.Protected(track.trackId, cipher)
        }

        val sources = selected.optJSONArray("sources")
            ?: return Resolution.Unavailable("Deezer resolver returned no source")
        val source = sources.optJSONObject(1) ?: sources.optJSONObject(0)
            ?: return Resolution.Unavailable("Deezer resolver returned no source")
        val streamUrl = source.optString("url").takeIf { it.startsWith("http") }
            ?: return Resolution.Unavailable("Deezer resolver returned an empty stream URL")

        val format = selected.optString("format").uppercase(Locale.US)
        val isFlac = format.contains("FLAC")
        val is320 = format.contains("320")
        val bitrate =
            when {
                isFlac -> 1_411_000
                is320 -> 320_000
                else -> 128_000
            }
        val contentLength = selected.optLong("filesize", -1L).takeIf { it > 0L }
        val expiresAt =
            selected.optLong("exp", -1L)
                .takeIf { it > 0L }
                ?.times(1000L)
                ?.minus(30_000L)
                ?.coerceAtLeast(System.currentTimeMillis() + 60_000L)
                ?: (System.currentTimeMillis() + STREAM_CACHE_MS)

        return Resolution.Direct(
            Resolved(
                mediaUri = streamUrl,
                trackId = track.trackId,
                label =
                    when {
                        isFlac -> "Deezer FLAC"
                        is320 -> "Deezer MP3 320"
                        else -> "Deezer MP3 128"
                    },
                mimeType = if (isFlac) "audio/flac" else "audio/mpeg",
                codecs = if (isFlac) "flac" else "mp3",
                bitrate = bitrate,
                sampleRate = 44_100,
                contentLength = contentLength,
                expiresAtMs = expiresAt,
            ),
        )
    }

    private fun searchTerms(query: Query): List<String> =
        buildList {
            val primaryArtist = query.artists.firstOrNull().orEmpty().searchQueryArtist()
            val cleanTitle = query.title.searchQueryTitle()
            add(listOf(cleanTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" "))
            add(cleanTitle)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun collectArtistNames(obj: JSONObject): List<String> =
        buildList {
            obj.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            val contributors = obj.optJSONArray("contributors")
            if (contributors != null) {
                for (i in 0 until contributors.length()) {
                    contributors.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.distinct()

    private fun String?.normalized(): String =
        this.orEmpty()
            .lowercase(Locale.US)
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun String.titleMatchNormalized(): String =
        normalized()
            .replace(Regex("\\b(official|audio|video|lyrics|lyric|visualizer|hd|hq)\\b"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun String.searchQueryTitle(): String =
        replace(Regex("(?i)\\s*[\\[(].*?(official|video|audio|lyrics|visualizer).*?[\\])]"), "")
            .replace(Regex("(?i)\\b(official\\s+video|official\\s+audio|lyrics?|visualizer)\\b"), "")
            .trim()

    private fun String.searchQueryArtist(): String =
        replace(Regex("(?i)\\s*[-–—]?\\s*(topic|official|vevo)$"), "")
            .trim()
}
