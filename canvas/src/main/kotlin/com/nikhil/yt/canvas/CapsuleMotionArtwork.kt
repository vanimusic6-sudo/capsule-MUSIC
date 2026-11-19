/*
 * Capsule MUSIC — animated artwork resolver, independent of the audio engine.
 * Original implementation; public Better Lyrics artwork discovery only.
 * GPL-3.0. The upstream videos remain the property of their rights holders.
 */
package com.nikhil.yt.canvas

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class CapsuleMotionSource { APPLE_ARTWORK, SPOTIFY_CANVAS }

data class CapsuleMotionClip(
    val uri: String,
    val source: CapsuleMotionSource,
    val albumId: String? = null,
) {
    val isHls: Boolean get() = URI(uri).path.lowercase(Locale.ROOT).endsWith(".m3u8")
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private const val API = "https://artwork.boidu.dev/"
private const val CANVAS = "https://artwork.boidu.dev/canvas"
private const val SUCCESS_TTL_MS = 12L * 60 * 60 * 1_000
private const val MISS_TTL_MS = 6L * 60 * 60 * 1_000
private const val RETRY_TTL_MS = 60_000L

private val qualifiers = Regex(
    """(?:\s*[-–—:]\s*|\s*[\[(]\s*)(?:official\s+)?(?:music\s+)?(?:video|audio|visuali[sz]er|lyric\s+video|lyrics?)(?:\s*[\])])?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val featuring = Regex("""\s*[\[(](?:feat\.?|ft\.?|featuring)\s+[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
private val primaryArtistSplit = Regex("""\s*(?:,|&|/)\s*|\s+(?:feat\.?|ft\.?|featuring| x )\s+""", RegexOption.IGNORE_CASE)
private val identitySymbols = Regex("""[^\p{L}\p{N}]+""")

internal fun capsuleMotionSongIdentity(raw: String): String =
    Normalizer.normalize(raw.replace(featuring, "").replace(qualifiers, ""), Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(identitySymbols, " ")
        .trim()

internal fun capsuleMotionArtistIdentity(raw: String): String =
    capsuleMotionSongIdentity(raw.split(primaryArtistSplit, limit = 2).firstOrNull().orEmpty())

/** Never forward provider-supplied file://, http:// or private-host URLs to Media3. */
internal fun capsuleMotionSafeUrl(raw: String?, source: CapsuleMotionSource): String? {
    val candidate = raw?.trim()?.takeIf { it.length in 12..2048 } ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.port != -1) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val trusted = when (source) {
        CapsuleMotionSource.APPLE_ARTWORK ->
            host == "mvod.itunes.apple.com" ||
                host.endsWith(".itunes.apple.com") ||
                host.endsWith(".mzstatic.com") ||
                host.endsWith(".apple.com") ||
                host.endsWith(".apple-dns.net")
        CapsuleMotionSource.SPOTIFY_CANVAS ->
            host == "canvaz.scdn.co"
    }
    return candidate.takeIf { trusted }
}

/** Kept separate from HTTP so contract tests can exercise error/mismatch cases. */
internal fun capsuleParseAppleMotion(
    body: String, song: String, artist: String,
): CapsuleMotionClip? {
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (obj["error"] != null) return null
    fun string(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val resolvedSong = string("name").takeIf(String::isNotBlank) ?: return null
    val resolvedArtist = string("artist").takeIf(String::isNotBlank) ?: return null
    if (capsuleMotionSongIdentity(resolvedSong) != capsuleMotionSongIdentity(song)) return null
    if (capsuleMotionArtistIdentity(resolvedArtist) != capsuleMotionArtistIdentity(artist)) return null
    val clip = sequenceOf(
        string("videoUrlVertical"), string("videoUrl"),
        string("animatedVertical"), string("animated"),
    ).mapNotNull { capsuleMotionSafeUrl(it, CapsuleMotionSource.APPLE_ARTWORK) }
        .firstOrNull() ?: return null
    return CapsuleMotionClip(clip, CapsuleMotionSource.APPLE_ARTWORK, string("albumId").ifBlank { null })
}

internal fun capsuleParseSpotifyMotion(body: String): CapsuleMotionClip? {
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (obj["error"] != null) return null
    val trackId = (obj["trackId"] as? JsonPrimitive)?.contentOrNull
    if (trackId.isNullOrBlank() || !trackId.matches(Regex("[a-zA-Z0-9]{22}"))) return null
    val candidate = (obj["videoUrl"] as? JsonPrimitive)?.contentOrNull
    val url = capsuleMotionSafeUrl(candidate, CapsuleMotionSource.SPOTIFY_CANVAS) ?: return null
    return CapsuleMotionClip(url, CapsuleMotionSource.SPOTIFY_CANVAS)
}

/**
 * Only called after the user first opens Immersion. Lookup never blocks audio,
 * is not polled in the background, and caches misses. Spotify and Apple race;
 * the first identity-validated, safe HTTPS clip starts without waiting for a
 * slower provider. An explicit Spotify-only retry handles unsupported Apple media.
 */
object CapsuleMotionArtwork {
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
            expectSuccess = false
        }
    }
    private data class CacheEntry(val clip: CapsuleMotionClip?, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolve(
        song: String, artist: String, album: String? = null,
        durationSeconds: Int = 0, canvasOnly: Boolean = false,
    ): CapsuleMotionClip? {
        if (song.isBlank() || artist.isBlank()) return null
        val key = listOf(
            capsuleMotionSongIdentity(song), capsuleMotionArtistIdentity(artist),
            album.orEmpty().trim().lowercase(Locale.ROOT), durationSeconds.toString(),
            canvasOnly.toString(),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.clip }
        // Previously we WAITED up to 8 seconds for Apple before even asking
        // Spotify. Start Spotify immediately and start Apple shortly after.
        // Take the first VALID clip and cancel the losing request. When Spotify
        // has no Canvas, Apple is already in flight and its result is reused.
        val (clip, short) = if (canvasOnly) {
            query(CANVAS, song, artist, album, durationSeconds,
                CapsuleMotionSource.SPOTIFY_CANVAS)
        } else coroutineScope {
            val results = Channel<Pair<CapsuleMotionClip?, Boolean>>(2)
            val spotify = launch {
                results.send(query(CANVAS, song, artist, album, durationSeconds,
                    CapsuleMotionSource.SPOTIFY_CANVAS))
            }
            val apple = launch {
                delay(180L)
                results.send(query(API, song, artist, album, durationSeconds,
                    CapsuleMotionSource.APPLE_ARTWORK))
            }
            var retriable = false
            var winner: CapsuleMotionClip? = null
            for (attempt in 0 until 2) {
                val (candidate, retry) = results.receive()
                retriable = retriable || retry
                if (candidate != null) {
                    winner = candidate
                    break
                }
            }
            spotify.cancel()
            apple.cancel()
            results.close()
            Pair(winner, retriable)
        }
        if (cache.size > 96) cache.clear()
        cache[key] = CacheEntry(clip, now + when {
            clip != null -> SUCCESS_TTL_MS
            short -> RETRY_TTL_MS
            else -> MISS_TTL_MS
        })
        return clip
    }

    private suspend fun query(
        url: String, song: String, artist: String, album: String?,
        duration: Int, source: CapsuleMotionSource,
    ): Pair<CapsuleMotionClip?, Boolean> {
        return try {
            val response = client.get(url) {
                parameter("s", song)
                parameter("a", artist)
                album?.takeIf(String::isNotBlank)?.let { parameter("al", it) }
                if (duration > 0) parameter("d", duration)
            }
            if (response.status == HttpStatusCode.TooManyRequests ||
                response.status.value >= 500) return Pair(null, true)
            if (response.status != HttpStatusCode.OK) return Pair(null, false)
            val body = response.bodyAsText()
            val clip = when (source) {
                CapsuleMotionSource.APPLE_ARTWORK -> capsuleParseAppleMotion(body, song, artist)
                CapsuleMotionSource.SPOTIFY_CANVAS -> capsuleParseSpotifyMotion(body)
            }
            Pair(clip, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Pair(null, true)
        }
    }
}
