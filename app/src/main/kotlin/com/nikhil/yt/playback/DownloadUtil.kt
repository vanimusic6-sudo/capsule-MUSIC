/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloaderFactory
import com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor
import com.nikhil.yt.playback.audio.AudioCacheIdentity
import com.nikhil.yt.playback.audio.AudioPlaybackContext
import com.nikhil.yt.playback.audio.AudioResolvePriority
import com.nikhil.yt.playback.audio.ResolvingAudioDownloader
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.constants.AudioStreamPolicyKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.FormatEntity
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.di.DownloadCache
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val audioStreamPolicy by enumPreference(
        context,
        AudioStreamPolicyKey,
        AudioStreamPolicy.VISIONOS,
    )
    private fun playbackContext() = AudioPlaybackContext(
        audioQuality, audioStreamPolicy.normalizedForPlayback(), connectivityManager.isActiveNetworkMetered,
    )

    // Download pressure protection. This reacts to transport failures without
    // cycling YouTube client identities or bypassing an explicit challenge.
    @Volatile private var currentMaxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
    @Volatile private var cooldownUntilMs = 0L
    private val consecutiveThrottleSignals = AtomicInteger(0)

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .retryOnConnectionFailure(false)
            .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(StreamClientUtils.withFallbackHeaders(chain.request()))
            }.build()
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val downloaderFactory = DownloaderFactory { request ->
        ResolvingAudioDownloader(
            mediaId = request.id,
            cache = downloadCache,
            resolve = {
                val remainingMs = cooldownUntilMs - System.currentTimeMillis()
                if (remainingMs > 0) delay(remainingMs)
                val selection = playbackContext()
                val playbackData = CapsuleAudioEngine.playerResponseForPlayback(
                    videoId = request.id,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                    streamPolicy = selection.policy,
                    priority = AudioResolvePriority.DOWNLOAD,
                ).getOrThrow()
                if (selection != playbackContext()) throw java.io.IOException("Playback context changed during download resolve")
                storeDownloadMetadata(request.id, playbackData)
                playbackData
            },
            dataSource = { playback ->
                CacheDataSource.Factory().setCache(downloadCache)
                    .setUpstreamDataSourceFactory(
                        ResolvingDataSource.Factory(
                            CacheDataSource.Factory().setCache(playerCache)
                                .setCacheWriteDataSinkFactory(null)
                                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(mediaOkHttpClient))
                                .setFlags(FLAG_IGNORE_CACHE_ON_ERROR),
                        ) { spec ->
                            spec.buildUpon()
                                .setKey(AudioCacheIdentity.key(request.id, playback))
                                .setHttpRequestHeaders(spec.httpRequestHeaders + playback.streamHeaders)
                                .build()
                        },
                    )
            },
        )
    }

    private fun storeDownloadMetadata(mediaId: String, playbackData: CapsuleAudioEngine.PlaybackData) {
        val format = playbackData.format

        with(database) {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.substringBefore(';'),
                    codecs =
                        format.mimeType
                            .substringAfter("codecs=", "")
                            .removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength ?: C.LENGTH_UNSET.toLong(),
                    loudnessDb = playbackData.audioConfig?.loudnessDb ?: format.loudnessDb,
                    perceptualLoudnessDb =
                        playbackData.audioConfig?.perceptualLoudnessDb
                            ?: format.perceptualLoudnessDb,
                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                ),
            )

            val existing = getSongByIdBlocking(mediaId)?.song

            val updatedSong =
                if (existing != null) {
                    existing
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl =
                            playbackData.videoDetails
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url,
                    )
                }

            upsert(updatedSong)
        }

    }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            DefaultDownloadIndex(databaseProvider),
            downloaderFactory,
        ).apply {
            maxParallelDownloads = currentMaxParallelDownloads
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_FAILED) {
                            CapsuleAudioEngine.invalidateCachedStreamUrls(download.request.id)
                            if (finalException != null && CapsuleAudioEngine.isRateLimitedException(finalException)) {
                                CapsuleAudioEngine.markRateLimitedFailure()
                            }
                            registerThrottleSignal(finalException)
                        } else if (download.state == Download.STATE_COMPLETED) {
                            clearThrottleSignal()
                            database.query {
                                getSongByIdBlocking(download.request.id)?.song?.let { song ->
                                    if (song.dateDownload == null) update(song.copy(dateDownload = LocalDateTime.now()))
                                }
                            }
                        }

                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }
                    }
                },
            )
        }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = mutableMapOf<String, Download>()
                val cursor = downloadManager.downloadIndex.getDownloads()
                cursor.use {
                    while (it.moveToNext()) {
                        result[it.download.request.id] = it.download
                    }
                }
                downloads.update { result + it }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    private fun registerThrottleSignal(exception: Throwable?) {
        val nextStrikeCount =
            if (exception == null || isProbablyThrottleSignal(exception)) {
                consecutiveThrottleSignals.incrementAndGet()
            } else {
                consecutiveThrottleSignals.updateAndGet { strikes -> maxOf(1, strikes) }
            }

        val reducedParallelDownloads =
            when {
                nextStrikeCount >= 4 -> MIN_PARALLEL_DOWNLOADS
                nextStrikeCount >= 2 -> DEFAULT_MAX_PARALLEL_DOWNLOADS - 1
                else -> currentMaxParallelDownloads
            }.coerceIn(MIN_PARALLEL_DOWNLOADS, DEFAULT_MAX_PARALLEL_DOWNLOADS)

        val cooldownMs =
            when {
                nextStrikeCount >= 4 -> LONG_COOLDOWN_MS
                nextStrikeCount >= 2 -> SHORT_COOLDOWN_MS
                else -> 0L
            }

        if (reducedParallelDownloads != currentMaxParallelDownloads) {
            currentMaxParallelDownloads = reducedParallelDownloads
            downloadManager.maxParallelDownloads = reducedParallelDownloads
        }

        if (cooldownMs > 0) {
            cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + cooldownMs)
        }
    }

    private fun clearThrottleSignal() {
        val remainingStrikes =
            consecutiveThrottleSignals.updateAndGet { strikes ->
                if (strikes > 0) strikes - 1 else 0
            }

        if (
            remainingStrikes == 0 &&
            currentMaxParallelDownloads != DEFAULT_MAX_PARALLEL_DOWNLOADS
        ) {
            currentMaxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
            downloadManager.maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
        }
    }

    private fun isProbablyThrottleSignal(exception: Throwable): Boolean {
        val message =
            buildString {
                append(exception.message.orEmpty())
                exception.cause?.message?.let {
                    if (isNotBlank()) append(' ')
                    append(it)
                }
            }.lowercase()

        return listOf(
            "429",
            "403",
            "quota",
            "rate",
            "too many",
            "temporarily unavailable",
            "timed out",
            "timeout",
            "unavailable",
            "reset by peer",
        ).any(message::contains)
    }

    companion object {
        private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 3
        private const val MIN_PARALLEL_DOWNLOADS = 1
        private const val SHORT_COOLDOWN_MS = 2_500L
        private const val LONG_COOLDOWN_MS = 8_000L
    }
}
