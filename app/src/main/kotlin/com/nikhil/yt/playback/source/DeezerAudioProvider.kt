/*
 * capsule fork
 * Deezer matching and resolver routing adapted from MetroFuse / Metrolist (GPL-3.0).
 *
 * This file intentionally does NOT contain protected-stream decryption.
 * A Deezer stream is accepted for playback only when the resolver explicitly
 * returns a clear/direct media URL. Protected media is reported to the caller,
 * which immediately falls back to Capsule's existing YouTube playback path.
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
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object DeezerAudioProvider {
    const val DEFAULT_RESOLVER_URL =
        "https://yesitworkssomehow-funny-deeza-api-and-yeah.hf.space/get_url"

    private const val SEARCH_API_URL = "https://api.deezer.com/search/track"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val MIN_MATCH_SCORE = 80
    private const val REJECT_SCORE = -1_000_000
    private const val SEARCH_LIMIT = 4
    private const val STREAM_CACHE_MS = 20 * 60 * 1000L
    private const val FAILURE_CACHE_MS = 2 * 60 * 1000L
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

    data class AccessTest(
        val apiReachable: Boolean,
        val apiLatencyMs: Long?,
        val resolverReachable: Boolean,
        val resolverLatencyMs: Long?,
        val fullStreamState: FullStreamState,
        val detail: String,
    )

    enum class FullStreamState {
        DIRECT,
        PROTECTED,
        UNAVAILABLE,
    }

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
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
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
        val resolver = normalizeResolverUrl(query.resolverUrl)
            ?: return Resolution.Unavailable("Invalid Deezer resolver URL")
        val cacheKey = "${query.mediaId}::${resolver}::${query.fastMode}::${query.quality.name}"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]
            ?.takeIf { it.expiresAtMs > now }
            ?.let { return it.resolution }

        val track = trackCache[query.mediaId]
            ?: findBestTrack(query)?.also { trackCache[query.mediaId] = it }
            ?: return cacheFailure(cacheKey, "Deezer match not found for ${query.title}")

        val result = requestFullStream(
            resolverUrl = resolver,
            track = track,
            query = query,
        )

        val ttl = when (result) {
            is Resolution.Direct ->
                (result.stream.expiresAtMs - now).coerceAtLeast(60_000L)
            is Resolution.Protected -> FAILURE_CACHE_MS
            is Resolution.Unavailable -> FAILURE_CACHE_MS
        }
        streamCache[cacheKey] = CachedResolution(result, now + ttl)
        return result
    }

    fun testAccess(resolverUrl: String = DEFAULT_RESOLVER_URL): AccessTest {
        val testQuery = Query(
            mediaId = "capsule-source-test",
            title = "One More Time",
            artists = listOf("Daft Punk"),
            album = null,
            durationMs = null,
            resolverUrl = resolverUrl,
            fastMode = true,
            quality = DeezerAudioQuality.MP3_320,
        )

        val apiStart = System.currentTimeMillis()
        val match = findBestTrack(testQuery)
        val apiLatency = System.currentTimeMillis() - apiStart
        if (match == null) {
            return AccessTest(
                apiReachable = false,
                apiLatencyMs = apiLatency,
                resolverReachable = false,
                resolverLatencyMs = null,
                fullStreamState = FullStreamState.UNAVAILABLE,
                detail = "Deezer public search did not return a usable track",
            )
        }

        val resolver = normalizeResolverUrl(resolverUrl)
            ?: return AccessTest(
                apiReachable = true,
                apiLatencyMs = apiLatency,
                resolverReachable = false,
                resolverLatencyMs = null,
                fullStreamState = FullStreamState.UNAVAILABLE,
                detail = "Invalid resolver URL",
            )

        val resolverStart = System.currentTimeMillis()
        val result = requestFullStream(resolver, match, testQuery)
        val resolverLatency = System.currentTimeMillis() - resolverStart

        return when (result) {
            is Resolution.Direct -> AccessTest(
                apiReachable = true,
                apiLatencyMs = apiLatency,
                resolverReachable = true,
                resolverLatencyMs = resolverLatency,
                fullStreamState = FullStreamState.DIRECT,
                detail = "Direct full stream available (${result.stream.label})",
            )
            is Resolution.Protected -> AccessTest(
                apiReachable = true,
                apiLatencyMs = apiLatency,
                resolverReachable = true,
                resolverLatencyMs = resolverLatency,
                fullStreamState = FullStreamState.PROTECTED,
                detail = "Resolver works, but full media is protected (${result.cipher}); playback will use YouTube fallback",
            )
            is Resolution.Unavailable -> AccessTest(
                apiReachable = true,
                apiLatencyMs = apiLatency,
                resolverReachable = false,
                resolverLatencyMs = resolverLatency,
                fullStreamState = FullStreamState.UNAVAILABLE,
                detail = result.reason,
            )
        }
    }

    private fun cacheFailure(cacheKey: String, reason: String): Resolution.Unavailable {
        val result = Resolution.Unavailable(reason)
        streamCache[cacheKey] =
            CachedResolution(result, System.currentTimeMillis() + FAILURE_CACHE_MS)
        return result
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
        val url = SEARCH_API_URL
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                JSONObject(payload).optJSONArray("data")
            }
        }.onFailure {
            Timber.tag("CapsuleDeezer").d(it, "Deezer search failed for $term")
        }.getOrNull()
    }

    private fun selectBestTrack(results: JSONArray, query: Query): MatchedTrack? {
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedDurationMs = query.durationMs
        val wantedTitleTokens = significantTokens(wantedTitle)

        data class Candidate(val track: MatchedTrack, val score: Int)

        val candidates = mutableListOf<Candidate>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val trackId = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val candidateTitleRaw = obj.optString("title")
            val candidateTitle = candidateTitleRaw.titleMatchNormalized()
            val candidateArtists = collectArtistNames(obj).map { it.normalized() }.filter { it.isNotBlank() }
            val candidateAlbum = obj.optJSONObject("album")?.optString("title").normalized()
            val candidateDurationMs = obj.optLong("duration", -1L).takeIf { it > 0L }?.times(1000L)
            val exactTitleMatch = wantedTitle.isNotBlank() && candidateTitle == wantedTitle

            var score = 0
            score += when {
                exactTitleMatch -> 120
                candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 70
                else -> (tokenOverlap(wantedTitleTokens, significantTokens(candidateTitle)) * 70).roundToInt()
            }

            if (wantedArtists.isNotEmpty()) {
                val artistOverlap = wantedArtists.any { wanted ->
                    candidateArtists.any { candidate ->
                        candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
                    }
                }
                score += if (artistOverlap) 80 else -70
            }

            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 35
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 18
                    else -> 0
                }
            }

            if (wantedDurationMs != null && candidateDurationMs != null) {
                val diff = abs(wantedDurationMs - candidateDurationMs)
                score += when {
                    diff <= 5_000L -> 45
                    diff <= 20_000L -> 20
                    diff <= 45_000L -> 4
                    else -> -90
                }
            }

            val versionMismatch = hasVersionMismatch(wantedTitle, candidateTitle)
            if (versionMismatch) score -= 80
            if (candidateTitle.isBlank()) score = REJECT_SCORE

            if (score >= MIN_MATCH_SCORE) {
                candidates += Candidate(
                    track = MatchedTrack(
                        trackId = trackId,
                        title = candidateTitleRaw,
                        artistNames = collectArtistNames(obj),
                        album = obj.optJSONObject("album")?.optString("title"),
                        durationMs = candidateDurationMs,
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
        val targetUrl = resolverUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply {
                if (query.fastMode) addQueryParameter("mode", "fast")
            }
            ?.build()
            ?: return Resolution.Unavailable("Invalid Deezer resolver URL")

        val formats = JSONArray().also { array ->
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

        val body = JSONObject()
            .put("formats", formats)
            .put("ids", JSONArray().put(track.trackId.toLongOrNull() ?: track.trackId))
            .put("fast", query.fastMode)

        val request = Request.Builder()
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
                        "Deezer resolver HTTP ${response.code}: ${payload.take(120)}"
                    )
                }
                parseMediaEnvelope(payload, track, query)
            }
        }.getOrElse { error ->
            Resolution.Unavailable(
                "Deezer resolver failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun parseMediaEnvelope(
        payload: String,
        track: MatchedTrack,
        query: Query,
    ): Resolution {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return Resolution.Unavailable("Deezer resolver returned invalid JSON")
        val mediaArray = root.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optJSONArray("media")
            ?: return Resolution.Unavailable("Deezer resolver returned no media")

        val preferredFormats =
            when (query.quality) {
                DeezerAudioQuality.FLAC -> listOf("FLAC", "MP3_320", "MP3_128")
                DeezerAudioQuality.MP3_320 -> listOf("MP3_320", "MP3_128")
                DeezerAudioQuality.MP3_128 -> listOf("MP3_128")
            }

        val mediaCandidates = buildList {
            for (i in 0 until mediaArray.length()) {
                val candidate = mediaArray.optJSONObject(i) ?: continue
                if (candidate.optJSONArray("sources")?.length()?.let { it > 0 } == true) {
                    add(candidate)
                }
            }
        }

        var selectedMedia: JSONObject? = null
        for (wanted in preferredFormats) {
            selectedMedia = mediaCandidates.firstOrNull { candidate ->
                candidate.optString("format").equals(wanted, ignoreCase = true)
            }
            if (selectedMedia != null) break
        }
        val resolvedMedia = selectedMedia ?: mediaCandidates.firstOrNull()
            ?: return Resolution.Unavailable("Deezer resolver returned no source")
        val cipher = resolvedMedia.optJSONObject("cipher")?.optString("type").orEmpty().trim()
        if (cipher.isNotBlank() &&
            !cipher.equals("none", ignoreCase = true) &&
            !cipher.equals("clear", ignoreCase = true) &&
            !cipher.equals("plain", ignoreCase = true)
        ) {
            return Resolution.Protected(track.trackId, cipher)
        }

        val sources = resolvedMedia.optJSONArray("sources")
            ?: return Resolution.Unavailable("Deezer resolver returned no source")
        val source = sources.optJSONObject(1) ?: sources.optJSONObject(0)
            ?: return Resolution.Unavailable("Deezer resolver returned no source")
        val streamUrl = source.optString("url").takeIf { it.startsWith("http") }
            ?: return Resolution.Unavailable("Deezer resolver returned an empty stream URL")

        val format = resolvedMedia.optString("format").uppercase(Locale.US)
        val isFlac = format.contains("FLAC")
        val is320 = format.contains("320")
        val bitrate = when {
            isFlac -> 1_411_000
            is320 -> 320_000
            else -> 128_000
        }
        val contentLength = resolvedMedia.optLong("filesize", -1L).takeIf { it > 0L }
        val expiresAt = resolvedMedia.optLong("exp", -1L)
            .takeIf { it > 0L }
            ?.times(1000L)
            ?.minus(30_000L)
            ?.coerceAtLeast(System.currentTimeMillis() + 60_000L)
            ?: (System.currentTimeMillis() + STREAM_CACHE_MS)

        return Resolution.Direct(
            Resolved(
                mediaUri = streamUrl,
                trackId = track.trackId,
                label = if (isFlac) "Deezer FLAC" else if (is320) "Deezer MP3 320" else "Deezer MP3 128",
                mimeType = if (isFlac) "audio/flac" else "audio/mpeg",
                codecs = if (isFlac) "flac" else "mp3",
                bitrate = bitrate,
                sampleRate = 44_100,
                contentLength = contentLength,
                expiresAtMs = expiresAt,
            )
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

    private fun significantTokens(value: String): Set<String> =
        value.split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun tokenOverlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.intersect(b).size.toDouble()
        return common / maxOf(a.size, b.size).toDouble()
    }

    private fun hasVersionMismatch(wanted: String, candidate: String): Boolean {
        val markers = listOf(
            "live", "remix", "sped up", "slowed", "nightcore", "acoustic",
            "instrumental", "karaoke", "cover", "radio edit", "extended"
        )
        return markers.any { marker ->
            val wantedHas = wanted.contains(marker)
            val candidateHas = candidate.contains(marker)
            wantedHas != candidateHas
        }
    }
}
