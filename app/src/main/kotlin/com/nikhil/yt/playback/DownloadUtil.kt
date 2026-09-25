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
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
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
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.soundcloud.SOUNDCLOUD_MEDIA_ID_PREFIX
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.soundcloud.hasSoundCloudDownload
import com.nikhil.yt.soundcloud.soundCloudDownloadFile
import com.nikhil.yt.soundcloud.soundCloudDownloadPartFile
import com.nikhil.yt.soundcloud.toSoundCloudMetadata
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import com.nikhil.yt.playback.audio.CapsulePlaybackSafety
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber
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
    private val downloadResolveMutex = Mutex()

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
    val soundCloudPending = MutableStateFlow<Set<String>>(emptySet())

    private val soundCloudDownloadScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun enqueueSoundCloud(
        track: SoundCloudCatalog.Track,
    ) {
        if (track.downloadable == false) return

        val mediaId = soundCloudMediaId(track.permalink)
        val current = downloads.value[mediaId]
        val currentHasCanonicalTrackUri =
            current == null ||
                SoundCloudNewPipe.isTrackUrl(current.request.uri.toString())

        if (
            (current?.state == Download.STATE_COMPLETED &&
                hasSoundCloudDownload(context, mediaId)) ||
            (
                currentHasCanonicalTrackUri &&
                    (
                        current?.state == Download.STATE_QUEUED ||
                            current?.state == Download.STATE_DOWNLOADING
                    )
            )
        ) {
            return
        }

        if (current != null && !currentHasCanonicalTrackUri) {
            Timber.tag("SoundCloudDownload").w(
                "replacing-legacy-request id=%s state=%d scheme=%s host=%s",
                mediaId,
                current.state,
                current.request.uri.scheme ?: "?",
                current.request.uri.host ?: "?",
            )
        }

        synchronized(soundCloudPending) {
            if (mediaId in soundCloudPending.value) return
            soundCloudPending.value = soundCloudPending.value + mediaId
        }

        Timber.tag("SoundCloudDownload").i(
            "enqueue id=%s title=%s previousState=%s hasFile=%s",
            mediaId,
            track.title,
            current?.state?.toString() ?: "none",
            hasSoundCloudDownload(context, mediaId),
        )

        // Builds before the cache split could leave ordinary playback bytes in
        // the persistent download cache under this exact id. They are not a
        // valid resumable download and can also keep a stale format boundary,
        // so start the first fixed download from a clean offline entry.
        runCatching {
            downloadCache.removeResource(mediaId)
        }

        database.transaction {
            insert(track.toSoundCloudMetadata())
        }

        // Persist the canonical SoundCloud permalink. The Downloader resolves
        // a fresh progressive CDN URL when it actually runs and can recover
        // from an expired signed URL without changing the download identity.
        val request =
            DownloadRequest.Builder(
                mediaId,
                android.net.Uri.parse(track.permalink),
            )
                .setCustomCacheKey(mediaId)
                .setData(track.title.toByteArray())
                .build()

        DownloadService.sendAddDownload(
            context,
            ExoDownloadService::class.java,
            request,
            false,
        )

        soundCloudDownloadScope.launch {
            // DownloadManager publishes QUEUED asynchronously. Keep the
            // immediate resolving indicator long enough to bridge that gap.
            delay(1_500L)
            soundCloudPending.update { pending ->
                pending - mediaId
            }
        }
    }

    private suspend fun awaitDownloadResolveWindow() {
        downloadResolveMutex.withLock {
            while (true) {
                val nowMs = System.currentTimeMillis()
                val waitMs =
                    maxOf(
                        CapsulePlaybackSafety.remainingBlockMs(nowMs),
                        (cooldownUntilMs - nowMs).coerceAtLeast(0L),
                    )

                if (waitMs <= 0L) {
                    return@withLock
                }

                // Re-check periodically so a network change / explicit breaker reset
                // can resume the queue without waiting for the old full deadline.
                delay(minOf(waitMs, DOWNLOAD_WAIT_SLICE_MS))
            }
        }
    }

    private suspend fun resolveDownloadPlayback(
        mediaId: String,
    ): CapsuleAudioEngine.PlaybackData {
        while (true) {
            awaitDownloadResolveWindow()
            val selection = playbackContext()
            val result =
                CapsuleAudioEngine.playerResponseForPlayback(
                    videoId = mediaId,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                    streamPolicy = selection.policy,
                    priority = AudioResolvePriority.DOWNLOAD,
                )

            if (selection != playbackContext()) {
                throw java.io.IOException("Playback context changed during download resolve")
            }

            result.getOrNull()?.let { playbackData ->
                storeDownloadMetadata(mediaId, playbackData)
                return playbackData
            }

            val failure =
                result.exceptionOrNull()
                    ?: java.io.IOException("Download audio resolve failed without an exception")

            // A global 429/bot-check breaker is a queue pause, not a reason to
            // permanently fail the user's download. The next iteration waits at
            // the shared gate and retries only after the breaker becomes safe.
            if (CapsulePlaybackSafety.remainingBlockMs() > 0L) {
                registerThrottleSignal(failure)
                continue
            }

            throw failure
        }
    }

    private val downloaderFactory = DownloaderFactory { request ->
        if (request.id.startsWith(SOUNDCLOUD_MEDIA_ID_PREFIX)) {
            SoundCloudDownloader(
                context = context,
                request = request,
            )
        } else {
            ResolvingAudioDownloader(
                mediaId = request.id,
                cache = downloadCache,
                resolve = { resolveDownloadPlayback(request.id) },
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

    /*
     * SoundCloud download requests survive APK updates in Media3's download
     * index. Early SoundCloud builds stored the short-lived resolved CDN URL in
     * request.uri. The file downloader introduced later expects request.uri to
     * be the canonical SoundCloud track permalink, so those legacy rows can
     * never resolve and DownloadManager keeps retrying them.
     *
     * Purge them before DownloadManager is constructed, otherwise Media3 will
     * immediately resume the stale request on process start before the user can
     * replace it.
     */
    private val downloadIndex =
        DefaultDownloadIndex(databaseProvider).also { index ->
            runCatching {
                val staleSoundCloudIds = mutableListOf<String>()
                index.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        if (
                            download.request.id.startsWith(SOUNDCLOUD_MEDIA_ID_PREFIX) &&
                            !SoundCloudNewPipe.isTrackUrl(download.request.uri.toString())
                        ) {
                            staleSoundCloudIds += download.request.id
                            Timber.tag("SoundCloudDownload").w(
                                "purge-legacy-request id=%s state=%d scheme=%s host=%s",
                                download.request.id,
                                download.state,
                                download.request.uri.scheme ?: "?",
                                download.request.uri.host ?: "?",
                            )
                        }
                    }
                }

                staleSoundCloudIds.forEach { id ->
                    index.removeDownload(id)
                    runCatching { downloadCache.removeResource(id) }
                    runCatching { soundCloudDownloadPartFile(context, id).delete() }
                    runCatching { soundCloudDownloadFile(context, id).delete() }
                }
            }.onFailure { failure ->
                Timber.tag("SoundCloudDownload").w(
                    failure,
                    "legacy-request-migration-failed",
                )
            }
        }

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            downloadIndex,
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
                        val isSoundCloud =
                            download.request.id.startsWith(SOUNDCLOUD_MEDIA_ID_PREFIX)

                        if (isSoundCloud) {
                            Timber.tag("SoundCloudDownload").i(
                                "manager-state id=%s state=%d stopReason=%d failureReason=%d bytes=%d percent=%.2f",
                                download.request.id,
                                download.state,
                                download.stopReason,
                                download.failureReason,
                                download.bytesDownloaded,
                                download.percentDownloaded,
                            )
                        }

                        if (download.state == Download.STATE_FAILED) {
                            if (isSoundCloud) {
                                Timber.tag("SoundCloudDownload").w(
                                    finalException,
                                    "manager-failed id=%s bytes=%d percent=%.2f",
                                    download.request.id,
                                    download.bytesDownloaded,
                                    download.percentDownloaded,
                                )
                            } else {
                                CapsuleAudioEngine.invalidateCachedStreamUrls(download.request.id)
                                if (
                                    finalException != null &&
                                    CapsuleAudioEngine.isRateLimitedException(finalException)
                                ) {
                                    CapsuleAudioEngine.markRateLimitedFailure()
                                }
                                registerThrottleSignal(finalException)
                            }
                        } else if (download.state == Download.STATE_COMPLETED) {
                            if (!isSoundCloud) {
                                clearThrottleSignal()
                            }
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
                val cursor = downloadIndex.getDownloads()
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
            "no playable clients",
            "no playable audio stream",
            "client response unavailable",
        ).any(message::contains)
    }

    companion object {
        private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 3
        private const val MIN_PARALLEL_DOWNLOADS = 1
        private const val SHORT_COOLDOWN_MS = 2_500L
        private const val LONG_COOLDOWN_MS = 8_000L
        private const val DOWNLOAD_WAIT_SLICE_MS = 2_000L
    }
}
