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
import java.io.IOException
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
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.playback.audio.effectiveAudioQuality
import kotlinx.coroutines.flow.first
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
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A genuine timed-out /player request can briefly fail at a bad network edge.
 * Never repeat an HTTP refusal, bot-check, explicit cancellation, or client
 * entitlement failure. A single retry is allowed within the same budget.
 */
internal fun shouldRetryDownloadResolve(failure: Throwable): Boolean {
    val chain = generateSequence(failure as Throwable?) { it.cause }.take(8).toList()
    if (chain.any { it is CancellationException || it is InvalidResponseCodeException }) return false
    return chain.any {
        it is java.net.SocketTimeoutException ||
            it.javaClass.simpleName.endsWith("PlayerRequestTimeoutException")
    }
}

internal fun isLikelyDownloadThrottleSignal(exception: Throwable): Boolean {
    val message = buildString {
        append(exception.message.orEmpty())
        exception.cause?.message?.let {
            if (isNotBlank()) append(' ')
            append(it)
        }
    }.lowercase()
    // A socket/client timeout, reset or unavailable network is a transport
    // failure; only explicit server throttling/quota/refusal belongs here.
    return listOf("429", "403", "quota", "rate limit", "too many requests")
        .any(message::contains)
}

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
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val artworkRepairs = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val artworkRepairIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private suspend fun playbackContext() = AudioPlaybackContext(
        context.dataStore.data.first().effectiveAudioQuality(), AudioStreamPolicy.VISIONOS, connectivityManager.isActiveNetworkMetered,
        clientOrder = AudioDownloadClientPolicy.order,
    )

    // Download pressure protection. This reacts to transport failures without
    // cycling YouTube client identities or bypassing an explicit challenge.
    @Volatile private var cooldownUntilMs = 0L
    private val consecutiveThrottleSignals = AtomicInteger(0)
    // Dedicated download-only entries. Never consume the player's URLs, clients or tokens.
    // A short pause for a manual request should resume the same signed file without another /player.
    private val resumeUrls = com.nikhil.yt.playback.audio.PlaybackDataCache(
        capacity = 8, nowMs = android.os.SystemClock::elapsedRealtime,
    )

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
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
        // Do not convert a valid 10-minute server 429 cooldown into a permanent
        // Media3 download failure after 30 seconds. The suspended download owns
        // its cancellation; no resolver mutex is held across the wait.
        awaitDownloadCooldownWindow(
            nowMs = System::currentTimeMillis,
            globalRemainingMs = CapsulePlaybackSafety::remainingBlockMs,
            downloadRemainingMs = CapsuleAudioEngine::downloadCooldownRemainingMs,
            localCooldownUntilMs = { cooldownUntilMs },
            sliceMs = DOWNLOAD_WAIT_SLICE_MS,
        )
    }

    private suspend fun resolveDownloadPlayback(
        mediaId: String,
    ): CapsuleAudioEngine.PlaybackData {
        repeat(MAX_DOWNLOAD_RESOLVE_ATTEMPTS) { attempt ->
            awaitDownloadResolveWindow()
            val selection = playbackContext()
            val resumeContext = selection to connectivityManager.activeNetwork
            resumeUrls.getForPlayback(mediaId, maxPrefetchedAgeMs = 60_000L, context = resumeContext)
                ?.let { return it }
            val result =
                CapsuleAudioEngine.resolveDownload(
                    videoId = mediaId,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                )

            if (selection != playbackContext() || resumeContext.second != connectivityManager.activeNetwork) {
                throw java.io.IOException("Playback context changed during download resolve")
            }

            result.getOrNull()?.let { playbackData ->
                AudioDownloadUrlPolicy.requireYouTubeMedia(playbackData.streamUrl)
                storeDownloadMetadata(mediaId, playbackData)
                if (resumeContext.second == connectivityManager.activeNetwork) {
                    resumeUrls.put(mediaId, playbackData, context = resumeContext)
                }
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

            if (attempt + 1 < MAX_DOWNLOAD_RESOLVE_ATTEMPTS && shouldRetryDownloadResolve(failure)) {
                timber.log.Timber.tag("AudioDownload").w(
                    "resolve-timeout-retry id=%s attempt=%d budget=%d (no client ban)",
                    mediaId, attempt + 1, MAX_DOWNLOAD_RESOLVE_ATTEMPTS,
                )
                // Cancellable, off the playback thread; only the one affected
                // download waits. A timeout never implies a server rate limit.
                delay(650L)
                return@repeat
            }
            throw failure
        }
        throw IOException("Download resolve retry budget exhausted")
    }

    private val downloaderFactory = DownloaderFactory { request ->
            var refreshedClient: String? = null
            ResolvingAudioDownloader(
                mediaId = request.id,
                cache = downloadCache,
                saveArtwork = { saveArtwork(request.id) },
                resolve = { resolveDownloadPlayback(request.id) },
                awaitAdmission = { awaitDownloadResolveWindow() },
                recover = { playback, failure ->
                    val status = generateSequence(failure as Throwable?) { it.cause }
                        .take(12).filterIsInstance<InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode
                    if (status == 403 || status == 410) {
                        resumeUrls.remove(request.id)
                        if (refreshedClient == playback.streamClient) {
                            CapsuleAudioEngine.markDownloadStreamClientFailed(request.id, playback.streamClient, status)
                        } else refreshedClient = playback.streamClient
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
                        resumeUrls.remove(download.request.id)
                        scope.launch { DownloadedArtwork.remove(context, download.request.id) }
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
                        timber.log.Timber.tag("AudioDownload").i(
                            "state id=%s state=%d stopReason=%d bytes=%d failureType=%s",
                            download.request.id, download.state, download.stopReason, download.bytesDownloaded,
                            finalException?.javaClass?.simpleName ?: "none",
                        )
                        removedWhileLoading.remove(download.request.id)
                        if (download.state == Download.STATE_QUEUED &&
                            downloads.value[download.request.id]?.state == Download.STATE_FAILED) {
                            CapsuleAudioEngine.clearDownloadClientFailures(download.request.id)
                        }
                        if (download.state == Download.STATE_FAILED) {
                            resumeUrls.remove(download.request.id)
                            if (
                                finalException != null &&
                                CapsuleAudioEngine.isRateLimitedException(finalException)
                            ) {
                                CapsuleAudioEngine.markRateLimitedFailure()
                            }
                            registerThrottleSignal(finalException)
                        } else if (download.state == Download.STATE_COMPLETED) {
                            resumeUrls.remove(download.request.id)
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

    private val downloadQueue = AudioDownloadQueue(downloadManager)

    init {
        scope.launch {
            for (mediaId in artworkRepairs) {
                try {
                    val network = connectivityManager.activeNetwork
                    if (downloads.value[mediaId]?.state == Download.STATE_COMPLETED && network != null &&
                        connectivityManager.getNetworkCapabilities(network)
                            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                        saveArtwork(mediaId)
                    }
                } finally { artworkRepairIds.remove(mediaId) }
            }
        }
        scope.launch {
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
                val network = connectivityManager.activeNetwork
                // A fresh launch must not sweep thousands of saved covers on mobile data.
                // Every new download already persists its artwork; this small batch
                // only repairs old library entries opportunistically on Wi-Fi.
                if (network != null && connectivityManager.getNetworkCapabilities(network)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
                    connectivityManager.getNetworkCapabilities(network)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) {
                    val completedIds = result.values.asSequence()
                        .filter { it.state == Download.STATE_COMPLETED }
                        .map { it.request.id }.sorted().toList()
                    if (completedIds.isNotEmpty()) {
                        // Rotate through old downloads across launches. The old
                        // take(6) permanently starved every cover after item six.
                        // Do not turn this into a bulk network job.
                        val prefs = context.getSharedPreferences("capsule-artwork-repair", Context.MODE_PRIVATE)
                        val start = prefs.getInt("cursor", 0).coerceAtLeast(0) % completedIds.size
                        val count = minOf(6, completedIds.size)
                        repeat(count) { offset ->
                            repairArtwork(completedIds[(start + offset) % completedIds.size])
                        }
                        prefs.edit().putInt("cursor", (start + count) % completedIds.size).apply()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private suspend fun saveArtwork(mediaId: String) {
        try {
            val url = database.getSongByIdBlocking(mediaId)?.song?.thumbnailUrl
            if (url != null && !DownloadedArtwork.save(context, mediaId, url)) {
                timber.log.Timber.tag("DownloadArtwork").w("Cover unavailable; audio retained id=%s", mediaId)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            timber.log.Timber.tag("DownloadArtwork").w("Cover metadata unavailable type=%s", error.javaClass.simpleName)
        }
    }

    fun repairArtwork(mediaId: String) {
        if (artworkRepairIds.add(mediaId) && artworkRepairs.trySend(mediaId).isFailure) artworkRepairIds.remove(mediaId)
    }

    fun getDownload(songId: String): Flow<Download?> =
        downloads
            .map { it[songId] }
            .distinctUntilChanged()

    private fun registerThrottleSignal(exception: Throwable?) {
        // The Media3 terminal exception may be null and a /player timeout
        // only proves connectivity failure. Neither is a new rate-limit strike.
        // Shared global HTTP 429 safety gates are awaited independently above.
        if (exception == null || !isProbablyThrottleSignal(exception)) return
        val nextStrikeCount = consecutiveThrottleSignals.incrementAndGet()

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

    private fun isProbablyThrottleSignal(exception: Throwable): Boolean =
        isLikelyDownloadThrottleSignal(exception)

    companion object {
        internal const val MAX_PARALLEL_DOWNLOADS = AudioDownloadSlots.LIMIT
        private const val SHORT_COOLDOWN_MS = 2_500L
        private const val LONG_COOLDOWN_MS = 8_000L
        private const val MAX_DOWNLOAD_RESOLVE_ATTEMPTS = 2
        private const val DOWNLOAD_WAIT_SLICE_MS = 2_000L
    }
}
