/*
 * capsule fork
 * Isolated alternative-source coordinator.
 *
 * YouTube is never resolved through this object. Capsule's original YouTube
 * pipeline remains the default player path and keeps playing while an
 * alternative source is checked in the background.
 *
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
    private const val DEEZER_BACKGROUND_BUDGET_MS = 4_500L
    private const val TRACK_FAILURE_BACKOFF_MS = 60_000L
    const val DIRECT_CACHE_KEY_PREFIX = "capsule:source:"

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
        val resolving: Boolean = false,
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
        val deezerPreview: EndpointHealth,
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
            Thread(runnable, "CapsuleDeezerBackground").apply { isDaemon = true }
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

    /**
     * Resolves Deezer in the background while the original YouTube stream keeps
     * playing. A null result means "keep YouTube exactly as it is".
     */
    suspend fun resolveForPlayback(
        context: Context,
        mediaId: String,
        song: Song?,
        metadata: MediaMetadata? = null,
        force: Boolean = false,
    ): DirectPlayback? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val preferred = AudioSource.fromPreference(prefs[AudioSourceKey])

        when (preferred) {
            AudioSource.YOUTUBE -> {
                markYouTubeApplied(mediaId, preferred, "YouTube selected")
                return@withContext null
            }

            AudioSource.AMAZON_MUSIC -> {
                markYouTubeApplied(
                    mediaId,
                    preferred,
                    "Amazon Music playback backend is not enabled; YouTube continues",
                )
                return@withContext null
            }

            AudioSource.DEEZER -> Unit
        }

        val now = System.currentTimeMillis()
        failureBackoff[mediaId]?.let { failure ->
            if (!force && failure.untilMs > now) {
                markYouTubeApplied(mediaId, preferred, failure.reason)
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
                "Deezer matching metadata is incomplete; YouTube continues",
            )
        }

        _playbackState.value =
            PlaybackSourceState(
                mediaId = mediaId,
                preferred = preferred,
                actual = AudioSource.YOUTUBE,
                label = "YouTube",
                detail = "Checking Deezer in background…",
                resolving = true,
            )

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
                future.get(DEEZER_BACKGROUND_BUDGET_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                return@withContext fallback(
                    mediaId,
                    preferred,
                    "Deezer check exceeded ${DEEZER_BACKGROUND_BUDGET_MS} ms; YouTube continues",
                )
            } catch (error: Throwable) {
                future.cancel(true)
                return@withContext fallback(
                    mediaId,
                    preferred,
                    "Deezer check failed: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}; YouTube continues",
                )
            }

        when (result) {
            is DeezerAudioProvider.Resolution.Direct -> {
                val stream = result.stream
                _playbackState.value =
                    PlaybackSourceState(
                        mediaId = mediaId,
                        preferred = preferred,
                        actual = AudioSource.YOUTUBE,
                        label = "YouTube",
                        detail = "Deezer stream ready · switching…",
                        bitrate = stream.bitrate,
                        sampleRate = stream.sampleRate,
                        mimeType = stream.mimeType,
                        resolving = false,
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
                    cacheKey = "${DIRECT_CACHE_KEY_PREFIX}deezer:$mediaId",
                )
            }

            is DeezerAudioProvider.Resolution.Protected ->
                fallback(
                    mediaId,
                    preferred,
                    "Deezer matched, but full stream uses ${result.cipher}; YouTube continues",
                )

            is DeezerAudioProvider.Resolution.Unavailable ->
                fallback(mediaId, preferred, "${result.reason}; YouTube continues")
        }
    }

    fun markAlternativeApplied(mediaId: String, playback: DirectPlayback) {
        _playbackState.value =
            PlaybackSourceState(
                mediaId = mediaId,
                preferred = playback.source,
                actual = playback.source,
                label = playback.label,
                detail = "Playing from ${playback.source.title}",
                bitrate = playback.bitrate,
                sampleRate = playback.sampleRate,
                mimeType = playback.mimeType,
                resolving = false,
            )
        failureBackoff.remove(mediaId)
    }

    fun markYouTubeApplied(
        mediaId: String,
        preferred: AudioSource = _playbackState.value.preferred,
        detail: String = "Playing from YouTube",
    ) {
        _playbackState.value =
            PlaybackSourceState(
                mediaId = mediaId,
                preferred = preferred,
                actual = AudioSource.YOUTUBE,
                label = "YouTube",
                detail = detail,
                resolving = false,
            )
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
                reason = "$detail; YouTube fallback active",
            )
        DeezerAudioProvider.invalidate(mediaId)
        markYouTubeApplied(mediaId, _playbackState.value.preferred, "$detail; YouTube fallback active")
    }

    fun invalidate(mediaId: String) {
        failureBackoff.remove(mediaId)
        DeezerAudioProvider.invalidate(mediaId)
    }

    fun onPreferredSourceChanged(source: AudioSource) {
        failureBackoff.clear()
        _playbackState.value =
            PlaybackSourceState(
                mediaId = _playbackState.value.mediaId,
                preferred = source,
                actual = AudioSource.YOUTUBE,
                label = "YouTube",
                detail =
                    if (source == AudioSource.YOUTUBE) {
                        "YouTube selected"
                    } else {
                        "Waiting for ${source.title} check"
                    },
                resolving = false,
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
                    name = "Deezer search",
                    available = deezer.apiReachable,
                    latencyMs = deezer.apiLatencyMs,
                    detail = if (deezer.apiReachable) "Track matching API reachable" else deezer.detail,
                ),
            deezerPreview =
                EndpointHealth(
                    name = "Deezer media edge",
                    available = deezer.previewReachable,
                    latencyMs = deezer.previewLatencyMs,
                    detail =
                        if (deezer.previewReachable) {
                            "Preview media bytes reachable — network path to Deezer works"
                        } else {
                            "Preview media path unavailable"
                        },
                ),
            deezerResolver =
                EndpointHealth(
                    name = "Deezer full resolver",
                    available = deezer.resolverReachable,
                    latencyMs = deezer.resolverLatencyMs,
                    detail = deezer.detail,
                ),
            deezerFullStream = deezer.fullStreamState,
            amazonWeb =
                amazon.copy(
                    detail =
                        if (amazon.available) {
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
        markYouTubeApplied(mediaId, preferred, reason)
        return null
    }
}
