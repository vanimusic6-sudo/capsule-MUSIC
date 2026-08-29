
/* * capsule fork
 * Alternative playback source coordinator.
 * YouTube remains Capsule's canonical playback fallback and download source.
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.playback.source

import android.content.Context
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object AudioSourceManager {
    private const val DEEZER_RESOLVE_BUDGET_MS = 3_200L
    private const val TRACK_FAILURE_BACKOFF_MS = 2 * 60 * 1000L

    data class DirectPlayback(
        val mediaUri: String,
        val source: AudioSource,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
        val cacheKey: String,
    )

    data class PlaybackSourceState(
        val mediaId: String? = null,
        val preferred: AudioSource = AudioSource.YOUTUBE,
        val actual: AudioSource = AudioSource.YOUTUBE,
        val label: String = "YouTube",
        val detail: String? = null,
        val bitrate: Int? = null,
        val sampleRate: Int? = null,
        val mimeType: String? = null,
    )

    data class EndpointHealth(
        val name: String,
        val available: Boolean,
        val latencyMs: Long?,
        val detail: String,
    )

    data class HealthReport(
        val youtube: EndpointHealth,
        val deezerApi: EndpointHealth,
        val deezerResolver: EndpointHealth,
        val deezerFullStream: DeezerAudioProvider.FullStreamState,
        val amazonWeb: EndpointHealth,
        val generatedAtMs: Long = System.currentTimeMillis(),
    )

    private data class FailureBackoff(
        val untilMs: Long,
        val reason: String,
    )

    private val _playbackState = MutableStateFlow(PlaybackSourceState())
    val playbackState = _playbackState.asStateFlow()

    private val failureBackoff = ConcurrentHashMap<String, FailureBackoff>()

    private val resolverExecutor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "CapsuleDeezerResolver").apply { isDaemon = true }
        }

    private val healthClient =
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    suspend fun preferredSource(context: Context): AudioSource =
        AudioSource.fromPreference(context.dataStore.data.first()[AudioSourceKey])

    suspend fun resolveForPlayback(
        context: Context,
        mediaId: String,
        song: Song?,
        metadata: MediaMetadata? = null,
    ): DirectPlayback? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val preferred = AudioSource.fromPreference(prefs[AudioSourceKey])

        when (preferred) {
            AudioSource.YOUTUBE -> {
                setYouTubeState(mediaId, preferred, "YouTube selected")
                return@withContext null
            }

            AudioSource.AMAZON_MUSIC -> {
                setYouTubeState(
                    mediaId = mediaId,
                    preferred = preferred,
                    detail = "Amazon Music playback backend is not enabled yet; using YouTube",
                )
                return@withContext null
            }

            AudioSource.DEEZER -> Unit
        }

        val now = System.currentTimeMillis()
        failureBackoff[mediaId]?.let { failure ->
            if (failure.untilMs > now) {
                setYouTubeState(mediaId, preferred, failure.reason)
                return@withContext null
            }
            failureBackoff.remove(mediaId)
        }

        val title = song?.song?.title ?: metadata?.title
        val artists = song?.artists?.map { it.name } ?: metadata?.artists?.map { it.name }.orEmpty()
        val album = song?.album?.title ?: song?.song?.albumName ?: metadata?.album?.title
        val durationMs =
            (song?.song?.duration ?: metadata?.duration)
                ?.takeIf { it > 0 }
                ?.times(1000L)

        if (title.isNullOrBlank() || artists.isEmpty()) {
            return@withContext fallback(
                mediaId,
                preferred,
                "Track metadata is unavailable for Deezer matching",
            )
        }

        val resolverUrl =
            prefs[DeezerResolverUrlKey]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DeezerAudioProvider.DEFAULT_RESOLVER_URL
        val fastMode = prefs[DeezerFastModeKey] ?: true
        val quality = DeezerAudioQuality.fromPreference(prefs[DeezerAudioQualityKey])

        val query =
            DeezerAudioProvider.Query(
                mediaId = mediaId,
                title = title,
                artists = artists,
                album = album,
                durationMs = durationMs,
                resolverUrl = resolverUrl,
                fastMode = fastMode,
                quality = quality,
            )

        val future =
            resolverExecutor.submit<DeezerAudioProvider.Resolution> {
                DeezerAudioProvider.resolve(query)
            }
        val result =
            try {
                future.get(DEEZER_RESOLVE_BUDGET_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                return@withContext fallback(
                    mediaId,
                    preferred,
                    "Deezer exceeded ${DEEZER_RESOLVE_BUDGET_MS} ms; using YouTube",
                )
            } catch (error: Throwable) {
                future.cancel(true)
                return@withContext fallback(
                    mediaId,
                    preferred,
                    "Deezer resolver failed: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}; using YouTube",
                )
            }

        when (result) {
            is DeezerAudioProvider.Resolution.Direct -> {
                val stream = result.stream
                _playbackState.value =
                    PlaybackSourceState(
                        mediaId = mediaId,
                        preferred = preferred,
                        actual = AudioSource.DEEZER,
                        label = stream.label,
                        detail = "Direct Deezer stream",
                        bitrate = stream.bitrate,
                        sampleRate = stream.sampleRate,
                        mimeType = stream.mimeType,
                    )
                DirectPlayback(
                    mediaUri = stream.mediaUri,
                    source = AudioSource.DEEZER,
                    label = stream.label,
                    mimeType = stream.mimeType,
                    codecs = stream.codecs,
                    bitrate = stream.bitrate,
                    sampleRate = stream.sampleRate,
                    contentLength = stream.contentLength,
                    expiresAtMs = stream.expiresAtMs,
                    cacheKey = "capsule:deezer:$mediaId",
                )
            }

            is DeezerAudioProvider.Resolution.Protected ->
                fallback(
                    mediaId,
                    preferred,
                    "Deezer full stream is protected (${result.cipher}); using YouTube",
                )

            is DeezerAudioProvider.Resolution.Unavailable ->
                fallback(mediaId, preferred, "${result.reason}; using YouTube")
        }
    }

    fun isAlternativeActive(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank()) return false
        val state = _playbackState.value
        return state.mediaId == mediaId && state.actual != AudioSource.YOUTUBE
    }

    fun markPlaybackFailure(mediaId: String, reason: String?) {
        val detail = reason?.takeIf { it.isNotBlank() } ?: "Alternative source playback failed"
        failureBackoff[mediaId] =
            FailureBackoff(
                untilMs = System.currentTimeMillis() + TRACK_FAILURE_BACKOFF_MS,
                reason = "$detail; using YouTube",
            )
        DeezerAudioProvider.invalidate(mediaId)
        val preferred = _playbackState.value.preferred
        setYouTubeState(mediaId, preferred, "$detail; using YouTube")
    }

    fun invalidate(mediaId: String) {
        failureBackoff.remove(mediaId)
        DeezerAudioProvider.invalidate(mediaId)
        if (_playbackState.value.mediaId == mediaId) {
            _playbackState.value =
                _playbackState.value.copy(
                    actual = AudioSource.YOUTUBE,
                    label = "YouTube",
                    detail = "Source will be resolved again",
                    bitrate = null,
                    sampleRate = null,
                    mimeType = null,
                )
        }
    }

    fun onPreferredSourceChanged(source: AudioSource) {
        failureBackoff.clear()
        _playbackState.value =
            PlaybackSourceState(
                mediaId = _playbackState.value.mediaId,
                preferred = source,
                actual = AudioSource.YOUTUBE,
                label = "YouTube",
                detail = "Waiting for source resolution",
            )
    }

    suspend fun testSources(context: Context): HealthReport = withContext(Dispatchers.IO) {
        val youtube = testEndpoint("YouTube", "https://music.youtube.com/")
        val resolverUrl =
            context.dataStore.data.first()[DeezerResolverUrlKey]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DeezerAudioProvider.DEFAULT_RESOLVER_URL

        val deezer = DeezerAudioProvider.testAccess(resolverUrl)
        val amazon = testEndpoint("Amazon Music web", "https://music.amazon.com/")

        HealthReport(
            youtube = youtube,
            deezerApi =
                EndpointHealth(
                    name = "Deezer API",
                    available = deezer.apiReachable,
                    latencyMs = deezer.apiLatencyMs,
                    detail = if (deezer.apiReachable) "Search/matching API is reachable" else deezer.detail,
                ),
            deezerResolver =
                EndpointHealth(
                    name = "Deezer resolver",
                    available = deezer.resolverReachable,
                    latencyMs = deezer.resolverLatencyMs,
                    detail = deezer.detail,
                ),
            deezerFullStream = deezer.fullStreamState,
            amazonWeb = amazon.copy(
                detail = if (amazon.available) {
                    "Web endpoint reachable · playback backend not enabled"
                } else {
                    amazon.detail
                },
            ),
        )
    }

    private fun testEndpoint(name: String, url: String): EndpointHealth {
        val started = System.currentTimeMillis()
        return runCatching {
            healthClient
                .newCall(
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "Mozilla/5.0 Capsule/1.0")
                        .build(),
                ).execute().use { response ->
                    val latency = System.currentTimeMillis() - started
                    EndpointHealth(
                        name = name,
                        available = response.isSuccessful || response.code in 300..399,
                        latencyMs = latency,
                        detail = "HTTP ${response.code}",
                    )
                }
        }.getOrElse { error ->
            EndpointHealth(
                name = name,
                available = false,
                latencyMs = System.currentTimeMillis() - started,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun fallback(
        mediaId: String,
        preferred: AudioSource,
        reason: String,
    ): DirectPlayback? {
        Timber.tag("CapsuleSources").i("$mediaId: $reason")
        failureBackoff[mediaId] =
            FailureBackoff(
                untilMs = System.currentTimeMillis() + TRACK_FAILURE_BACKOFF_MS,
                reason = reason,
            )
        setYouTubeState(mediaId, preferred, reason)
        return null
    }

    private fun setYouTubeState(
        mediaId: String,
        preferred: AudioSource,
        detail: String,
    ) {
        _playbackState.value =
            PlaybackSourceState(
                mediaId = mediaId,
                preferred = preferred,
                actual = AudioSource.YOUTUBE,
                label = "YouTube",
                detail = detail,
            )
    }
}
