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
import androidx.media3.datasource.DataSource
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
import com.nikhil.yt.playback.audio.AudioChunkedDataSource
import com.nikhil.yt.playback.audio.AudioDownloadUrlPolicy
import com.nikhil.yt.playback.audio.AUDIO_CHUNK_BYTES
import com.nikhil.yt.playback.audio.AudioPlaybackContext
import com.nikhil.yt.playback.audio.ResolvingAudioDownloader
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.nikhil.yt.playback.audio.AudioDownloadClientPolicy
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.FormatEntity
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.di.DownloadCache
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import com.nikhil.yt.playback.audio.CapsulePlaybackSafety
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private fun playbackContext() = AudioPlaybackContext(
        audioQuality, AudioStreamPolicy.VISIONOS, connectivityManager.isActiveNetworkMetered,
        clientOrder = AudioDownloadClientPolicy.order,
    )

    // Download pressure protection. This reacts to transport failures without
    // cycling YouTube client identities or bypassing an explicit challenge.
    @Volatile private var cooldownUntilMs = 0L
    private val consecutiveThrottleSignals = AtomicInteger(0)
    private val downloadResolveMutex = Mutex()

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                AudioDownloadUrlPolicy.requireYouTubeMedia(chain.request().url.toString())
                chain.proceed(chain.request())
            }
            .addInterceptor { chain ->
                chain.proceed(StreamClientUtils.withFallbackHeaders(chain.request()))
            }.build()
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    @Volatile private var initialIndexLoaded = false
    private val removedWhileLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private suspend fun awaitDownloadResolveWindow() {
        val ready = withTimeoutOrNull(DOWNLOAD_GATE_TIMEOUT_MS) {
            downloadResolveMutex.withLock {
                var waitMs: Long
                do {
                    val nowMs = System.currentTimeMillis()
                    waitMs = maxOf(
                        CapsulePlaybackSafety.remainingBlockMs(nowMs),
                        CapsuleAudioEngine.downloadCooldownRemainingMs(),
                        (cooldownUntilMs - nowMs).coerceAtLeast(0L),
                    )
                    if (waitMs > 0L) delay(minOf(waitMs, DOWNLOAD_WAIT_SLICE_MS))
                } while (waitMs > 0L)
                true
            }
        }
        if (ready != true) throw IOException("Download cooldown has not elapsed; retry later")
    }

    private suspend fun resolveDownloadPlayback(
        mediaId: String,
    ): CapsuleAudioEngine.PlaybackData {
        repeat(MAX_DOWNLOAD_RESOLVE_ATTEMPTS) {
            awaitDownloadResolveWindow()
            val selection = playbackContext()
            val result =
                CapsuleAudioEngine.resolveDownload(
                    videoId = mediaId,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                )

            if (selection != playbackContext()) {
                throw java.io.IOException("Playback context changed during download resolve")
            }

            result.getOrNull()?.let { playbackData ->
                AudioDownloadUrlPolicy.requireYouTubeMedia(playbackData.streamUrl)
                storeDownloadMetadata(mediaId, playbackData)
                return playbackData
            }

            val failure =
                result.exceptionOrNull()
                    ?: java.io.IOException("Download audio resolve failed without an exception")

            // A global 429/bot-check breaker is a queue pause, not a reason to
            // permanently fail the user's download. The next iteration waits at
            // the shared gate and retries only after the breaker becomes safe.
            if (CapsulePlaybackSafety.remainingBlockMs() > 0L || CapsuleAudioEngine.downloadCooldownRemainingMs() > 0L) {
                registerThrottleSignal(failure)
                return@repeat
            }

            throw failure
        }
        throw IOException("Download resolve retry budget exhausted")
    }

    private val downloaderFactory = DownloaderFactory { request ->
            ResolvingAudioDownloader(
                mediaId = request.id,
                cache = downloadCache,
                resolve = { resolveDownloadPlayback(request.id) },
                recover = { playback, failure ->
                    val status = generateSequence(failure as Throwable?) { it.cause }
                        .take(12).filterIsInstance<InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode
                    if (status == 403 || status == 410) {
                        CapsuleAudioEngine.markDownloadStreamClientFailed(request.id, playback.streamClient, status)
                        true
                    } else {
                        if (status == 429) CapsuleAudioEngine.markRateLimitedFailure()
                        false
                    }
                },
                dataSource = { playback ->
                    CacheDataSource.Factory().setCache(downloadCache)
                        .setUpstreamDataSourceFactory(
                            ResolvingDataSource.Factory(
                                CacheDataSource.Factory().setCache(playerCache)
                                    .setCacheWriteDataSinkFactory(null)
                                    .setUpstreamDataSourceFactory(DataSource.Factory {
                                        AudioChunkedDataSource(
                                            OkHttpDataSource.Factory(
                                                mediaOkHttpClient.newBuilder().proxy(YouTube.streamProxy).build(),
                                            ).createDataSource(),
                                            chunkBytes = playback.rangeChunkSizeBytes.takeIf { it > 0L }
                                                ?: AUDIO_CHUNK_BYTES,
                                        )
                                    })
                                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR),
                            ) { spec ->
                                spec.buildUpon().apply {
                                    val length = playback.format.contentLength
                                    if (spec.length == C.LENGTH_UNSET.toLong() && length != null && length > spec.position) {
                                        setLength(length - spec.position)
                                    }
                                }
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

            val existingRow = getSongByIdBlocking(mediaId)
            val existing = existingRow?.song

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
            if (existingRow?.artists.isNullOrEmpty()) {
                playbackData.videoDetails?.author?.takeIf { it.isNotBlank() }?.let { author ->
                    mergeSongArtistCredits(mediaId, listOf(MediaMetadata.Artist(
                        id = playbackData.videoDetails.channelId,
                        name = author,
                    )))
                }
            }
        }

    }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    private val downloadIndex = DefaultDownloadIndex(databaseProvider)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            downloadIndex,
            downloaderFactory,
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        CapsuleAudioEngine.clearDownloadClientFailures(download.request.id)
                        if (!initialIndexLoaded) removedWhileLoading.add(download.request.id)
                        downloads.update { it - download.request.id }
                        database.query {
                            getSongByIdBlocking(download.request.id)?.song?.let { song ->
                                if (downloads.value[download.request.id]?.state != Download.STATE_COMPLETED) {
                                    update(song.copy(dateDownload = null))
                                }
                            }
                        }
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        removedWhileLoading.remove(download.request.id)
                        if (download.state == Download.STATE_QUEUED &&
                            downloads.value[download.request.id]?.state == Download.STATE_FAILED) {
                            CapsuleAudioEngine.clearDownloadClientFailures(download.request.id)
                        }
                        if (download.state == Download.STATE_FAILED) {
                            if (
                                finalException != null &&
                                CapsuleAudioEngine.isRateLimitedException(finalException)
                            ) {
                                CapsuleAudioEngine.markRateLimitedFailure()
                            }
                            registerThrottleSignal(finalException)
                        } else if (download.state == Download.STATE_COMPLETED) {
                            clearThrottleSignal()
                            database.query {
                                getSongByIdBlocking(download.request.id)?.song?.let { song ->
                                    update(song.copy(dateDownload = LocalDateTime.now()))
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
                val cursor = downloadIndex.getDownloads()
                cursor.use {
                    while (it.moveToNext()) {
                        result[it.download.request.id] = it.download
                    }
                }
                downloads.update { (result + it) - removedWhileLoading }
                initialIndexLoaded = true
                removedWhileLoading.clear()
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    fun getDownload(songId: String): Flow<Download?> =
        downloads
            .map { it[songId] }
            .distinctUntilChanged()

    private fun registerThrottleSignal(exception: Throwable?) {
        val nextStrikeCount =
            if (exception == null || isProbablyThrottleSignal(exception)) {
                consecutiveThrottleSignals.incrementAndGet()
            } else {
                consecutiveThrottleSignals.updateAndGet { strikes -> maxOf(1, strikes) }
            }

        val cooldownMs =
            when {
                nextStrikeCount >= 4 -> LONG_COOLDOWN_MS
                nextStrikeCount >= 2 -> SHORT_COOLDOWN_MS
                else -> 0L
            }

        if (cooldownMs > 0) {
            cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + cooldownMs)
        }
    }

    private fun clearThrottleSignal() {
        consecutiveThrottleSignals.updateAndGet { strikes -> (strikes - 1).coerceAtLeast(0) }
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
            "no playable clients",
            "no playable audio stream",
            "client response unavailable",
        ).any(message::contains)
    }

    companion object {
        internal const val MAX_PARALLEL_DOWNLOADS = 1
        private const val SHORT_COOLDOWN_MS = 2_500L
        private const val LONG_COOLDOWN_MS = 8_000L
        private const val DOWNLOAD_GATE_TIMEOUT_MS = 30_000L
        private const val MAX_DOWNLOAD_RESOLVE_ATTEMPTS = 2
        private const val DOWNLOAD_WAIT_SLICE_MS = 2_000L
    }
}
