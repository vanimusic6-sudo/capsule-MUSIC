/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



@file:Suppress("DEPRECATION", "UnsafeOptInUsageError")

package com.nikhil.yt.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.nikhil.yt.App
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.constants.AudioClientOrderKey
import com.nikhil.yt.constants.AudioCrossfadeDurationKey
import com.nikhil.yt.constants.AudioNormalizationKey
import com.nikhil.yt.constants.AudioOffload
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.constants.AudioStreamPolicyKey
import com.nikhil.yt.constants.CapsuleVideoQuality
import com.nikhil.yt.constants.CapsuleVideoQualityKey
import com.nikhil.yt.constants.AutoDownloadOnLikeKey
import com.nikhil.yt.constants.AutoLoadMoreKey
import com.nikhil.yt.constants.AutoSkipNextOnErrorKey
import com.nikhil.yt.constants.AutoStartOnBluetoothKey
import com.nikhil.yt.constants.DiscordTokenKey
import com.nikhil.yt.constants.EnableDiscordRPCKey
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerBassBoostEnabledKey
import com.nikhil.yt.constants.EqualizerBassBoostStrengthKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.EqualizerOutputGainEnabledKey
import com.nikhil.yt.constants.EqualizerOutputGainMbKey
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.EqualizerVirtualizerEnabledKey
import com.nikhil.yt.constants.EqualizerVirtualizerStrengthKey
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.constants.HistoryDuration
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.constants.ListenBrainzEnabledKey
import com.nikhil.yt.constants.ListenBrainzTokenKey
import com.nikhil.yt.constants.MaxSongCacheSizeKey
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleLike
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleShuffle
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleStartRadio
import com.nikhil.yt.constants.PauseListenHistoryKey
import com.nikhil.yt.constants.PauseOnDeviceMuteKey
import com.nikhil.yt.constants.PermanentShuffleKey
import com.nikhil.yt.constants.PersistentQueueKey
import com.nikhil.yt.constants.PlayerVolumeKey
import com.nikhil.yt.constants.RepeatModeKey
import com.nikhil.yt.constants.SkipSilenceKey
import com.nikhil.yt.constants.SmartTrimmerKey
import com.nikhil.yt.constants.StopMusicOnTaskClearKey
import com.nikhil.yt.constants.TogetherClientIdKey
import com.nikhil.yt.constants.YtmSyncKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.AlbumEntity
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.db.entities.Event
import com.nikhil.yt.db.entities.FormatEntity
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.di.DownloadCache
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.di.VideoCache
import com.nikhil.yt.extensions.SilentHandler
import com.nikhil.yt.extensions.collect
import com.nikhil.yt.extensions.collectLatest
import com.nikhil.yt.extensions.currentMetadata
import com.nikhil.yt.extensions.findNextMediaItemById
import com.nikhil.yt.extensions.mediaItems
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.extensions.CapsuleAudioOffloadAvailability
import com.nikhil.yt.extensions.currentAudioOffloadAvailability
import com.nikhil.yt.extensions.isAudioOffloadRequested
import com.nikhil.yt.extensions.setOffloadEnabled
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.extensions.toEnum
import com.nikhil.yt.extensions.toPersistQueue
import com.nikhil.yt.extensions.toQueue
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.together.TogetherSessionRuntime
import com.nikhil.yt.together.TogetherSessionController
import com.nikhil.yt.together.TogetherOnlineCredentials
import com.nikhil.yt.together.TogetherGuestControlCoordinator
import com.nikhil.yt.lyrics.LyricsPreloadManager
import com.nikhil.yt.models.PersistPlayerState
import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.EmptyQueue
import com.nikhil.yt.playback.queues.Queue
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.playback.queues.filterExplicit
import com.nikhil.yt.playback.queues.filterVideo
import com.nikhil.yt.playback.video.CAPSULE_VIDEO_CACHE_PREFIX
import com.nikhil.yt.playback.video.CAPSULE_VIDEO_SCHEME
import com.nikhil.yt.playback.video.CAPSULE_VIDEO_STREAM_CACHE_PREFIX
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState
import com.nikhil.yt.playback.video.CapsuleCacheRoutingDataSource
import com.nikhil.yt.playback.video.CapsuleVideoStreamInterceptor
import com.nikhil.yt.playback.video.YouTubeVideoResolver
import com.nikhil.yt.playback.video.CapsuleVideoResolveCoordinator
import com.nikhil.yt.playback.video.CapsuleVideoResolveRequest
import com.nikhil.yt.utils.CoilBitmapLoader
import com.nikhil.yt.utils.NetworkConnectivityObserver
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.SyncUtils
import com.nikhil.yt.utils.GlobalLog
import com.nikhil.yt.playback.audio.AudioCacheDataSource
import com.nikhil.yt.playback.audio.AudioCacheSource
import com.nikhil.yt.playback.audio.AudioNetworkDiagnosticDataSource
import com.nikhil.yt.playback.audio.AudioCdnConnectionDiagnosticInterceptor
import com.nikhil.yt.playback.audio.AudioCdnOpenContext
import com.nikhil.yt.playback.audio.AudioCdnOpenSource
import com.nikhil.yt.playback.audio.audioCdnInitialSettleDelayMs
import com.nikhil.yt.playback.audio.CapsuleAudioRequestInterceptor
import com.nikhil.yt.playback.audio.AudioCacheIdentity
import com.nikhil.yt.playback.audio.AudioFormatChangedException
import com.nikhil.yt.playback.audio.AudioStreamContract
import com.nikhil.yt.playback.audio.AudioPlaybackContext
import com.nikhil.yt.playback.audio.AudioResolveCoordinator
import com.nikhil.yt.playback.audio.AudioResolvePriority
import com.nikhil.yt.playback.audio.CapsuleAudioEngine
import com.nikhil.yt.playback.audio.PlaybackDataCache
import com.nikhil.yt.playback.presence.DiscordPresenceOwner
import com.nikhil.yt.playback.presence.PlaybackPresenceCoordinator
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.enumPreference
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.getAsync
import com.nikhil.yt.utils.getPresenceIntervalMillis
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.utils.reportRecoverableException
import com.nikhil.yt.ui.widget.updateVeluneWidgetState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

internal const val YOUTUBE_LOUDNESS_REFERENCE_LUFS = -7.0
internal const val NORMALIZATION_TARGET_LUFS = -14.0
internal const val MIN_NORMALIZATION_GAIN_DB = -12.0
internal const val SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS = 3_000L
internal const val SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS = 3_000L

internal fun signedUrlRefreshDelayMs(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Long = budgetDelayMs

internal fun shouldRefreshStreamSessionAfterSignedUrlRejection(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Boolean =
    httpStatusCode in setOf(403, 410) &&
        budgetDelayMs >= SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS

internal fun shouldRetryRejectedSignedUrl(
    httpStatusCode: Int?,
    budgetDelayMs: Long,
): Boolean =
    httpStatusCode !in setOf(403, 410) ||
        budgetDelayMs <= SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS

/**
 * YouTube's legacy loudnessDb is an offset around a -7 LUFS reference, not a
 * measured loudness value. Prefer perceptual loudness when available, matching
 * Metrolist semantics. A raw 0 dB therefore represents about -7 LUFS.
 */
internal fun measuredLoudnessLufs(
    loudnessDb: Double?,
    perceptualLoudnessDb: Double?,
): Double? {
    perceptualLoudnessDb?.takeIf { it.isFinite() }?.let { return it }
    return loudnessDb
        ?.takeIf { it.isFinite() }
        ?.plus(YOUTUBE_LOUDNESS_REFERENCE_LUFS)
}

internal data class TrackLoudness(
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double?,
) {
    val preferredValue: Double?
        get() =
            perceptualLoudnessDb?.takeIf { it.isFinite() }
                ?: loudnessDb?.takeIf { it.isFinite() }
}

internal fun calculateNormalizationFactor(
    loudness: TrackLoudness?,
    maxSafeGainFactor: Float,
): Float {
    val measuredLufs =
        measuredLoudnessLufs(
            loudnessDb = loudness?.loudnessDb,
            perceptualLoudnessDb = loudness?.perceptualLoudnessDb,
        ) ?: return 1f

    // Balanced target follows Metrolist's normal music setting: -14 LUFS.
    // Keep the existing +3 dB boost ceiling and cap attenuation at -12 dB.
    val gainDb =
        (NORMALIZATION_TARGET_LUFS - measuredLufs)
            .coerceAtLeast(MIN_NORMALIZATION_GAIN_DB)
    val rawFactor = 10f.pow(gainDb.toFloat() / 20f)
    if (!rawFactor.isFinite() || rawFactor <= 0f) return 1f
    return if (rawFactor > 1f) min(rawFactor, maxSafeGainFactor) else rawFactor
}

internal fun shouldEnableAudioOffload(
    requested: Boolean,
    crossfadeDurationMs: Int,
): Boolean = requested && crossfadeDurationMs == 0

/** HIGHEST is a retired alias: InnerTubeX maps it to the same stream tier as HIGH. */
internal fun AudioQuality.normalizedPlaybackQuality(): AudioQuality =
    if (this == AudioQuality.HIGHEST) AudioQuality.HIGH else this

/**
 * A deterministic extractor miss is recoverable once with a clean same-policy
 * resolve. Keep this classification narrow: generic REMOTE_ERROR must not turn
 * into an automatic request loop.
 */
internal fun PlaybackException.isNoPlayableStreamFailure(): Boolean =
    generateSequence(this as Throwable?) { it?.cause }
        .take(8)
        .any { throwable ->
            val message = throwable?.message.orEmpty()
            message.contains("No playable stream found for this track", ignoreCase = true) ||
                message.contains("InnerTubeX returned no playable AUDIO stream", ignoreCase = true)
        }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private lateinit var audioManager: AudioManager
    private val playbackFocusController by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackFocusController(
            audioManager = audioManager,
            isPlayingProvider = { player.isPlaying },
            onDecision = { decision ->
                audioFocusVolumeFactor.value = decision.volumeFactor
                when (decision.playbackAction) {
                    PlaybackFocusPlaybackAction.NONE -> Unit
                    PlaybackFocusPlaybackAction.PAUSE -> if (player.isPlaying) player.pause()
                    PlaybackFocusPlaybackAction.RESUME -> player.play()
                }
            },
        )
    }
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val songMutationMutex = Mutex()
    private val binder = MusicBinder()
    private val togetherShutdownGate = TogetherShutdownGate()
    private val playbackPositionGeneration = PlaybackPositionGeneration()

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection: MutableStateFlow<Boolean>
        get() = playbackRecoveryCoordinator.waitingForNetworkConnection
    private val isNetworkConnected = MutableStateFlow(false)

    @Volatile
    private var audioQuality = AudioQuality.AUTO

    @Volatile
    private var audioStreamPolicy = AudioStreamPolicy.VISIONOS

    @Volatile
    private var audioClientOrder: List<String> =
        AudioClientOrder.legacyOrder(AudioStreamPolicy.VISIONOS)
    private val capsuleVideoQuality by enumPreference(
        this,
        CapsuleVideoQualityKey,
        CapsuleVideoQuality.AUTO,
    )
    private fun playbackContext() = AudioPlaybackContext(
        quality = audioQuality.normalizedPlaybackQuality(),
        policy = audioStreamPolicy,
        metered = connectivityManager.isActiveNetworkMetered,
        clientOrder = audioClientOrder,
    )
    private val playbackUrlCache = PlaybackDataCache(currentContext = ::playbackContext)
    private val audioResolveCoordinator =
        AudioResolveCoordinator<CapsuleAudioEngine.PlaybackData>(
            scopeProvider = { ioScope },
            cachedValue = { mediaId -> playbackUrlCache.get(mediaId) },
        )

    /*
     * One resolve per track, shared by everyone who wants it.
     *
     * The ResolvingDataSource callback runs on ExoPlayer's loader thread, so
     * whatever it does there is time the next track cannot start buffering.
     * Rather than each caller starting its own chain, every resolve is owned by
     * the service scope and looked up here: the prefetch below starts it early,
     * and if the loader arrives before it finished, the loader simply awaits
     * the job that is already running instead of launching a second one.
     */
    private val audioResolveStability = PlaybackStabilityGate()

    /**
     * Final request gate for AUDIO CDN traffic.
     *
     * Resolver debounce alone is insufficient when prefetch has already cached PlaybackData:
     * ResolvingDataSource can return that URL immediately and the network upstream can open before
     * a later media-transition cancellation arrives. Blocking here means stale rapid-skip items are
     * rejected before OkHttp's upstream.open() and therefore before a request can reach YouTube.
     */
    private fun awaitAudioNetworkOpenPermit(dataSpec: androidx.media3.datasource.DataSpec) {
        val mediaId =
            dataSpec.key
                ?.let(AudioCacheIdentity::mediaId)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return

        try {
            runBlocking {
                suspend fun isRelevant(): Boolean =
                    withContext(Dispatchers.Main.immediate) {
                        mediaId == player.currentMediaItem?.mediaId ||
                            mediaId in upcomingAudioIds()
                    }

                audioResolveStability.awaitNetworkOpenStable(::isRelevant)

                val openContext = dataSpec.customData as? AudioCdnOpenContext
                val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                val ageMs =
                    openContext
                        ?.resolvedAtElapsedMs
                        ?.takeIf { it > 0L && nowElapsedMs >= it }
                        ?.let { nowElapsedMs - it }
                        ?: -1L
                val settleMs =
                    openContext?.let { context ->
                        audioCdnInitialSettleDelayMs(
                            nowElapsedMs = nowElapsedMs,
                            resolvedAtElapsedMs = context.resolvedAtElapsedMs,
                        )
                    } ?: 0L

                if (GlobalLog.isEnabled && openContext != null) {
                    val queryNames = runCatching { dataSpec.uri.queryParameterNames }.getOrDefault(emptySet())
                    val headerNames = dataSpec.httpRequestHeaders.keys
                    Timber.tag("AudioCDN").d(
                        "cdn-open-gate id=%s source=%s ageMs=%d settleMs=%d client=%s pot=%s n=%s sig=%s expire=%s ua=%s origin=%s referer=%s",
                        mediaId,
                        openContext.source,
                        ageMs,
                        settleMs,
                        openContext.streamClient ?: "unknown",
                        "pot" in queryNames,
                        "n" in queryNames,
                        "sig" in queryNames || "signature" in queryNames || "lsig" in queryNames,
                        "expire" in queryNames,
                        headerNames.any { it.equals("User-Agent", ignoreCase = true) },
                        headerNames.any { it.equals("Origin", ignoreCase = true) },
                        headerNames.any { it.equals("Referer", ignoreCase = true) },
                    )
                }

                if (settleMs > 0L) {
                    delay(settleMs)
                    if (!isRelevant()) {
                        throw kotlinx.coroutines.CancellationException("Track changed during AUDIO CDN settle window")
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw InterruptedIOException("Stale AUDIO CDN open suppressed before request").apply {
                initCause(cancelled)
            }
        } catch (interrupted: InterruptedException) {
            throw InterruptedIOException("AUDIO CDN open interrupted before request").apply {
                initCause(interrupted)
            }
        }
    }

    private fun audioResolveJob(
        mediaId: String,
    ) =
        audioResolveCoordinator.resolve(mediaId) { policyGeneration ->
            audioResolveStability.awaitStable(
                requiredDelayMs = {
                    withContext(Dispatchers.Main.immediate) {
                        if (mediaId == player.currentMediaItem?.mediaId) {
                            PLAYBACK_RESOLVE_STABILITY_DELAY_MS
                        } else {
                            PREFETCH_RESOLVE_STABILITY_DELAY_MS
                        }
                    }
                },
            ) {
                withContext(Dispatchers.Main.immediate) {
                    mediaId == player.currentMediaItem?.mediaId ||
                        mediaId in upcomingAudioIds()
                }
            }
            val selection = playbackContext()
            val priority = withContext(Dispatchers.Main.immediate) {
                if (mediaId == player.currentMediaItem?.mediaId) AudioResolvePriority.PLAYBACK
                else AudioResolvePriority.PREFETCH
            }
            val startedAt = System.currentTimeMillis()
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "resolve start id=%s",
                mediaId,
            )
            CapsuleAudioEngine
                .resolvePlayback(
                    videoId = mediaId,
                    audioQuality = selection.quality,
                    connectivityManager = connectivityManager,
                    streamPolicy = selection.policy,
                    clientOrder = selection.clientOrder,
                    priority = priority,
                )
                .also { result ->
                    if (selection != playbackContext()) {
                        throw kotlinx.coroutines.CancellationException("Playback context changed")
                    }
                    result.getOrNull()?.let {
                        cacheResolvedPlayback(mediaId, it, policyGeneration, selection)
                    }
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "resolve done id=%s ok=%s tookMs=%d",
                        mediaId,
                        result.isSuccess,
                        System.currentTimeMillis() - startedAt,
                    )
                }
        }

    /*
     * Resolve what is coming next while the current track is still playing, so
     * the loader thread finds a ready URL instead of a network chain. This is
     * the part that actually keeps playback moving; the timeout further down is
     * only a floor for the cases prefetch cannot cover.
     */
    private fun upcomingAudioIds(): List<String> {
        // Media3's next index respects shuffle/repeat; index + 1 does not.
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return emptyList()
        return listOfNotNull(
            player.getMediaItemAt(nextIndex).mediaId.trim().takeIf { it.isNotBlank() },
        )
    }

    private fun prefetchUpcomingAudio() {
        val prefetchGeneration = audioResolveCoordinator.nextPrefetchGeneration()
        val upcoming = upcomingAudioIds()

        val relevantIds =
            buildSet {
                player.currentMediaItem
                    ?.mediaId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                addAll(upcoming)
            }

        /*
         * Rapid skipping used to leave every abandoned prefetch alive. Each
         * one owns its own client fallback budget, so a short swipe burst
         * could keep contacting YouTube for tracks no longer near playback.
         * Keep only the current item and the one useful look-ahead item.
         */
        audioResolveCoordinator.cancelStaleExcept(relevantIds).forEach { mediaId ->
            Timber.tag(CAPSULE_RESOLVE_TAG).i(
                "prefetch cancel stale id=%s",
                mediaId,
            )
        }

        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "prefetch queue ahead=%d ids=%s",
            upcoming.size,
            upcoming.joinToString(","),
        )

        upcoming.forEach { mediaId ->
            ioScope.launch {
                if (!audioResolveCoordinator.isPrefetchGenerationCurrent(prefetchGeneration)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "prefetch skip transient id=%s",
                        mediaId,
                    )
                    return@launch
                }

                if (!isNetworkConnected.value ||
                    AudioCacheIdentity.completeKey(downloadCache, mediaId) != null ||
                    AudioCacheIdentity.completeKey(playerCache, mediaId) != null
                ) return@launch
                if (playbackUrlCache.get(mediaId, PREFETCH_FRESHNESS_MS) != null) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip cached id=%s", mediaId)
                    return@launch
                }
                if (audioResolveCoordinator.hasInFlight(mediaId)) {
                    Timber.tag(CAPSULE_RESOLVE_TAG).i("prefetch skip inflight id=%s", mediaId)
                    return@launch
                }

                // The shared job publishes the entire result before completing.
                audioResolveJob(mediaId).await()
            }
        }
    }

    /**
     * Video mode is deliberately isolated from the normal YouTube audio resolver.
     * Normal queue items never use the capsule-video scheme/cache key.
     */
    val videoPlaybackState = MutableStateFlow(CapsuleVideoPlaybackState())
    private var videoOriginalMediaItem: MediaItem? = null
    private var videoOriginalMediaId: String? = null
    private val videoResolveCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        CapsuleVideoResolveCoordinator(scopeProvider = { scope })
    }

    /*
     * VIDEO power/request protection.
     *
     * - Screen off: VIDEO immediately falls back to normal AUDIO and no new
     *   video-search requests are made while the display is off/locked.
     * - Screen unlocked again: the current track may resume VIDEO once.
     * - Detectable 403/429/bot responses open a short VIDEO-only circuit breaker.
     *   Normal YouTube AUDIO is never disabled by this breaker.
     */
    private var screenInteractive = true
    private var videoSuspendedForScreenOff = false
    private var screenStateReceiverRegistered = false

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenInteractive = false
                        suspendCapsuleVideoForScreenOff()
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        screenInteractive = true
                        resumeCapsuleVideoAfterUnlockIfAllowed()
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        screenInteractive = true
                        resumeCapsuleVideoAfterUnlockIfAllowed()
                    }
                }
            }
        }

    private val playbackRecoveryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackRecoveryCoordinator(
            scopeProvider = { scope },
            maxConsecutiveTrackFailures = MAX_CONSECUTIVE_TRACK_FAILURES,
            currentMediaIdProvider = { player.currentMediaItem?.mediaId },
            playWhenReadyProvider = { player.playWhenReady },
            currentIndexProvider = { player.currentMediaItemIndex },
            positionGenerationProvider = playbackPositionGeneration::snapshot,
            connectedProvider = { connectivityObserver.isCurrentlyConnected() },
            playbackBlockedProvider = {
                CapsuleAudioEngine.playbackBlockedExceptionOrNull() != null
            },
            healthyPlaybackProvider = { mediaId ->
                player.currentMediaItem?.mediaId == mediaId &&
                    player.playbackState == Player.STATE_READY &&
                    player.isPlaying
            },
            recoveryProgressProvider = { mediaId ->
                player.currentMediaItem?.mediaId == mediaId &&
                    player.playbackState == Player.STATE_READY
            },
            pausePlayback = { player.pause() },
            preparePlayback = { player.prepare() },
            healthyPlaybackDelayMs = HEALTHY_PLAYBACK_RESET_MS,
        )
    }
    private var streamRetryJob: Job? = null

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(StreamClientUtils.withFallbackHeaders(chain.request()))
            }.build()
    }

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private val playbackPersistence by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackPersistence(
            context = this,
            mainScope = { scope },
            persistenceEnabled = { dataStore.get(PersistentQueueKey, true) },
            snapshotProvider = ::capturePersistentPlaybackSnapshot,
            playerStateProvider = ::capturePersistentPlayerState,
            isPlayingProvider = { player.isPlaying },
        )
    }
    @Volatile
    private var suppressAutoPlayback = false
    private val discordPresenceOwner by lazy(LazyThreadSafetyMode.NONE) {
        DiscordPresenceOwner(
            context = this,
            scopeProvider = { scope },
            enabledProvider = { dataStore.get(EnableDiscordRPCKey, true) },
            tokenProvider = { dataStore.get(DiscordTokenKey, "") },
            songProvider = {
                player.currentMetadata?.let { createTransientSongFromMedia(it) }
                    ?: currentSong.value
            },
            positionProvider = { player.currentPosition },
            isPausedProvider = { !player.isPlaying },
            intervalProvider = { getPresenceIntervalMillis(this@MusicService) },
            onFailure = { operation, error ->
                Timber.tag("MusicService").e(error, operation)
            },
        )
    }
    private val playbackPresenceCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackPresenceCoordinator(
            context = this,
            scopeProvider = { scope },
            discordOwner = discordPresenceOwner,
            currentMediaIdProvider = { player.currentMediaItem?.mediaId },
            songProvider = { mediaId ->
                val stored =
                    if (mediaId != null) {
                        withContext(Dispatchers.IO) { database.song(mediaId).first() }
                    } else {
                        null
                    }
                stored
                    ?: player.currentMetadata
                        ?.takeIf { metadata -> mediaId == null || metadata.id == mediaId }
                        ?.let(::createTransientSongFromMedia)
            },
            positionProvider = { player.currentPosition },
            isPausedProvider = { !player.isPlaying },
            listenBrainzEnabledProvider = { dataStore.get(ListenBrainzEnabledKey, false) },
            listenBrainzTokenProvider = { dataStore.get(ListenBrainzTokenKey, "") },
            onFailure = { operation, error ->
                Timber.tag("MusicService").v(error, operation)
            },
        )
    }

    val currentMediaMetadata = MutableStateFlow<com.nikhil.yt.models.MediaMetadata?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }.flowOn(Dispatchers.IO)

    private val freshlyResolvedLoudness =
        ConcurrentHashMap<String, TrackLoudness>()
    private val freshlyResolvedLoudnessVersion = MutableStateFlow(0L)
    private val normalizeFactor = MutableStateFlow(1f)
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private val playbackFadeFactor = MutableStateFlow(1f)
    private val crossfadeDurationMs = MutableStateFlow(0)
    private val audioNormalizationEnabled = MutableStateFlow(true)
    private var crossfadeAudio: CrossfadeAudio? = null
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache
    private val playbackCacheManager by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackCacheManager(
            cache = playerCache,
            cacheDirectory = filesDir.resolve("exoplayer"),
        )
    }

    @Inject
    @VideoCache
    lateinit var videoCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    private val audioEffectsController =
        PlaybackAudioEffectsController { operation, error ->
            reportRecoverableException("MusicService", operation, error)
        }
    val eqCapabilities = audioEffectsController.capabilities

    private val scrobbleCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ScrobbleCoordinator(
            context = this,
            dataStore = dataStore,
            scopeProvider = { scope },
            ioScopeProvider = { ioScope },
            songProvider = { mediaId -> database.song(mediaId).first() },
            onFailure = { operation, error ->
                reportRecoverableException("MusicService", operation, error)
            },
        )
    }

    private val automixRuntime = AutomixRuntime()
    private val songMetadataRecoveryCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        SongMetadataRecoveryCoordinator(
            scopeProvider = { ioScope },
            database = database,
            awaitStable = { mediaId ->
                audioResolveStability.awaitStable {
                    withContext(Dispatchers.Main.immediate) {
                        player.currentMediaItem?.mediaId == mediaId
                    }
                }
            },
            mediaMetadataProvider = { mediaId ->
                withContext(Dispatchers.Main.immediate) {
                    player.findNextMediaItemById(mediaId)?.metadata
                }
            },
            automixJobProvider = { mediaId ->
                withContext(Dispatchers.Main.immediate) {
                    automixRuntime.jobForSeed(mediaId)
                }
            },
            playbackBlockedExceptionOrNull = {
                CapsuleAudioEngine.playbackBlockedExceptionOrNull()
            },
            onFailure = { mediaId, failure ->
                reportRecoverableException(
                    "MusicService",
                    "recover song metadata id=$mediaId",
                    failure,
                )
            },
        )
    }
    private val automixCoordinator =
        AutomixCoordinator(
            runtime = automixRuntime,
            scopeProvider = { scope },
            stabilityGate = audioResolveStability,
            cacheRelatedSongs = { mediaId, songs ->
                songMetadataRecoveryCoordinator.cacheRelatedSongs(mediaId, songs)
            },
            playbackBlockedExceptionOrNull = { CapsuleAudioEngine.playbackBlockedExceptionOrNull() },
        )
    val automixItems = automixCoordinator.items
    val automixLoading = automixCoordinator.loading
    val automixError = automixCoordinator.error
    val autoAddedMediaIds = automixCoordinator.autoAddedMediaIds

    val maxSafeGainFactor = 1.414f // +3 dB
    @Volatile
    private var hasCalledStartForeground = false

    private val togetherRuntime =
        TogetherSessionRuntime { operation, error ->
            reportRecoverableException("MusicService", operation, error)
        }
    val togetherSessionState = togetherRuntime.sessionState
    private val togetherGuestControl = TogetherGuestControlCoordinator()

    private val togetherSessionController by lazy(LazyThreadSafetyMode.NONE) {
        TogetherSessionController(
            runtime = togetherRuntime,
            mainScopeProvider = { scope },
            ioScopeProvider = { ioScope },
            hostId = togetherHostId,
            appNameProvider = { getString(R.string.app_name) },
            guestNameProvider = { getString(R.string.together_role_guest) },
            localIpv4Provider = ::getLocalIpv4Address,
            onlineBaseUrlProvider = {
                com.nikhil.yt.together.TogetherOnlineEndpoint.baseUrlOrNull(dataStore)
            },
            onlineTokenProvider = { TogetherOnlineCredentials.bearerTokenOrNull() },
            clientIdProvider = ::getOrCreateTogetherClientId,
            roomStateProvider = ::buildTogetherRoomState,
            onlineErrorMessage = ::togetherOnlineErrorMessage,
            onlineNotConfiguredMessage = { getString(R.string.together_online_not_configured) },
            tokenMissingMessage = { getString(R.string.together_token_missing) },
            invalidWebSocketMessage = { "Connection failed: Invalid server websocket URL" },
            invalidLinkMessage = { getString(R.string.invalid_link) },
            invalidCodeMessage = { getString(R.string.invalid_code) },
            notAllowedMessage = { getString(R.string.not_allowed) },
            hostLeftMessage = { getString(R.string.together_host_left_session) },
            networkUnavailableMessage = { getString(R.string.network_unavailable) },
            hostEventHandler = ::handleTogetherHostEvent,
            remoteStateApplier = ::applyRemoteRoomState,
            guestControlReset = { togetherGuestControl.reset() },
            guestNotice = { message, key -> showTogetherNotice(message, key) },
            stopCurrentSession = ::stopTogetherInternal,
            onOnlineFailure = ::reportException,
        )
    }

    private fun isTogetherApplyingRemote(): Boolean = togetherRuntime.applyingRemote
    private val togetherHostId: String = "host"
    private var lastTogetherNoticeAtElapsedMs: Long = 0L
    private var lastTogetherNoticeKey: String? = null

    private fun showTogetherNotice(message: String, key: String? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        val normalizedKey = key ?: message
        if (normalizedKey == lastTogetherNoticeKey && now - lastTogetherNoticeAtElapsedMs < 1200L) return
        lastTogetherNoticeKey = normalizedKey
        lastTogetherNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast.makeText(this@MusicService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getOrCreateTogetherClientId(): String {
        val existing = dataStore.getAsync(TogetherClientIdKey)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[TogetherClientIdKey] = generated }
        return generated
    }

    private fun ensureStartedAsForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_velune_concept)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioQuality =
            dataStore[AudioQualityKey]
                .toEnum(AudioQuality.AUTO)
                .normalizedPlaybackQuality()
        audioStreamPolicy = dataStore[AudioStreamPolicyKey].toEnum(AudioStreamPolicy.VISIONOS).normalizedForPlayback()
        connectivityManager = requireNotNull(getSystemService()) { "ConnectivityManager is unavailable" }
        ensureScopesActive()
        audioStreamPolicy = dataStore[AudioStreamPolicyKey]
            .toEnum(AudioStreamPolicy.VISIONOS)
            .normalizedForPlayback()
        audioClientOrder =
            AudioClientOrder.resolve(
                raw = dataStore[AudioClientOrderKey],
                legacyPolicy = audioStreamPolicy,
            )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        } catch (e: Exception) {
            reportException(e)
        }

        ensureStartedAsForeground()

        
        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setLoadControl(createCapsuleLoadControl())
                .setRenderersFactory(createRenderersFactory())
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this)
                    addListener(sleepTimer)
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    setOffloadEnabled(dataStore.get(AudioOffload, false))
                }

        screenInteractive =
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive
                ?: true
        registerCapsuleScreenStateReceiver()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playbackFocusController.initialize()

        mediaLibrarySessionCallback.apply {
            toggleLike = { source -> this@MusicService.toggleLike(source) }
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            ).apply {
                setSmallIcon(R.drawable.ic_velune_concept)
            }
        )
        
        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(offload)
            }
        }

        connectivityObserver = NetworkConnectivityObserver(this)

        dataStore.data
            .map { prefs ->
                val policy =
                    prefs[AudioStreamPolicyKey]
                        .toEnum(AudioStreamPolicy.VISIONOS)
                        .normalizedForPlayback()
                val quality =
                    prefs[AudioQualityKey]
                        .toEnum(AudioQuality.AUTO)
                        .normalizedPlaybackQuality()
                val clientOrder =
                    AudioClientOrder.resolve(
                        raw = prefs[AudioClientOrderKey],
                        legacyPolicy = policy,
                    )
                Triple(policy, quality, clientOrder)
            }
            .distinctUntilChanged()
            .collect(scope) { (policy, quality, clientOrder) ->
                if (
                    policy != audioStreamPolicy ||
                    quality != audioQuality ||
                    clientOrder != audioClientOrder
                ) {
                    audioStreamPolicy = policy
                    audioQuality = quality
                    audioClientOrder = clientOrder
                    reloadAudioResolveConfig(
                        clientOrder.firstOrNull() ?: policy.playbackClientOverrideId,
                    )
                }
            }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                playbackRecoveryCoordinator.onConnectivityChanged(isConnected)
            }
        }

        scope.launch {
            var previousNetworkId: Long? = null

            connectivityObserver.activeNetworkId.collect { networkId ->
                if (networkId == null) return@collect

                val previous = previousNetworkId
                previousNetworkId = networkId
                if (previous == null || previous == networkId) return@collect

                /*
                 * Wi-Fi/mobile/VPN hand-off: signed media URLs, anonymous
                 * visitor state and anti-bot cooldowns belong to the previous
                 * route. Cancel obsolete prefetches and make the next explicit
                 * retry resolve once through the new route. Do not auto-play:
                 * the user remains in control after an error.
                 */
                Timber.tag("MusicService").i(
                    "Default network changed (%d -> %d); resetting playback route",
                    previous,
                    networkId,
                )
                streamRetryJob?.cancel()
                streamRetryJob = null
                audioResolveCoordinator.invalidatePolicy(
                    invalidatePrefetch = true,
                    onInvalidate = playbackUrlCache::clear,
                )
                playbackRecoveryCoordinator.clearRetryBudget()

                withContext(Dispatchers.IO) {
                    CapsuleAudioEngine.onNetworkChanged()
                }
            }
        }

        combine(playerVolume, normalizeFactor, audioFocusVolumeFactor, playbackFadeFactor) { playerVolume, normalizeFactor, audioFocusVolumeFactor, playbackFadeFactor ->
            playerVolume * normalizeFactor * audioFocusVolumeFactor * playbackFadeFactor
        }.collectLatest(scope) { finalVolume ->
            player.volume = finalVolume
        }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) { song ->
            updateNotification()
            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                discordPresenceOwner.ensure()
            } else {
                discordPresenceOwner.stop()
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        dataStore.data
            .map { it[AudioOffload] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                updateAudioOffload(enabled)
                if (enabled) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        player.skipSilenceEnabled = false
                    }
                    val crossfadeSeconds = dataStore.get(AudioCrossfadeDurationKey, 0)
                    if (crossfadeSeconds != 0) {
                        dataStore.edit { it[AudioCrossfadeDurationKey] = 0 }
                    }
                }
            }
        
        dataStore.data
            .map { (it[AudioCrossfadeDurationKey] ?: 0) * 1000 }
            .distinctUntilChanged()
            .collectLatest(scope) {
                crossfadeDurationMs.value = it
                // Crossfade requires software mixing, so offload must stop immediately.
                updateAudioOffload(dataStore.get(AudioOffload, false))
            }

        crossfadeAudio =
            CrossfadeAudio(
                player = player,
                database = database,
                crossfadeDurationMs = crossfadeDurationMs,
                playbackFadeFactor = playbackFadeFactor,
                playerVolume = playerVolume,
                audioFocusVolumeFactor = audioFocusVolumeFactor,
                audioNormalizationEnabled = audioNormalizationEnabled,
                maxSafeGainFactor = maxSafeGainFactor,
                overlapPlayerFactory = {
                    ExoPlayer
                        .Builder(this)
                        .setMediaSourceFactory(createMediaSourceFactory())
                        .setLoadControl(createCapsuleLoadControl())
                        .setRenderersFactory(createRenderersFactory())
                        .setHandleAudioBecomingNoisy(false)
                        .setWakeMode(C.WAKE_MODE_LOCAL)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                            false,
                        ).setSeekBackIncrementMs(10000)
                        .setSeekForwardIncrementMs(10000)
                        .build()
                },
            ).also { it.start(scope) }

        lyricsPreloadManager = LyricsPreloadManager(
            context = this,
            database = database,
            networkConnectivity = connectivityObserver,
        )

        dataStore.data
            .map(::readEqSettingsFromPrefs)
            .distinctUntilChanged()
            .collectLatest(scope) { settings ->
                audioEffectsController.applySettings(settings)
            }

        combine(
            currentMediaMetadata,
            currentFormat,
            freshlyResolvedLoudnessVersion,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { metadata, format, _, normalizeAudio ->
            val fresh = metadata?.id?.let(freshlyResolvedLoudness::get)
            val stored =
                TrackLoudness(
                    loudnessDb = format?.loudnessDb,
                    perceptualLoudnessDb = format?.perceptualLoudnessDb,
                )
            (fresh?.takeIf { it.preferredValue != null } ?: stored) to normalizeAudio
        }.distinctUntilChanged().collectLatest(scope) { (loudness, normalizeAudio) ->
            audioNormalizationEnabled.value = normalizeAudio
            Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
            Timber.tag("AudioNormalization").d(
                "Resolved loudnessDb: ${loudness.loudnessDb}, " +
                    "perceptualLoudnessDb: ${loudness.perceptualLoudnessDb}",
            )
            
            normalizeFactor.value =
                if (normalizeAudio) {
                    if (loudness.preferredValue != null) {
                        val factor =
                            calculateNormalizationFactor(
                                loudness = loudness,
                                maxSafeGainFactor = maxSafeGainFactor,
                            )
                        Timber.tag("AudioNormalization").i("Applying normalization factor: $factor")
                        factor
                    } else {
                        Timber.tag("AudioNormalization").d(
                            "Loudness metadata is pending or unavailable; using unity gain",
                        )
                        1f
                    }
                } else {
                    Timber.tag("AudioNormalization").d("Normalization disabled - using factor 1.0")
                    1f
                }
        }

        dataStore.data
            .map { it[DiscordTokenKey].orEmpty() to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                discordPresenceOwner.reconcile(
                    enabled = enabled,
                    configuredToken = key,
                )
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                playbackCacheManager.trimToConfiguredLimit(
                    enabled = enabled,
                    maxSongCacheSizeMb = maxSongCacheSizeMb,
                )
            }

        scrobbleCoordinator.start()

        scope.launch(Dispatchers.IO) {
            if (dataStore.get(PersistentQueueKey, true)) {
                var restoredQueueSeedMediaId: String? = null
                playbackPersistence.read(PERSISTENT_QUEUE_FILE, PersistQueue::class.java)
                    ?.let { persistedQueue ->
                    restoredQueueSeedMediaId =
                        persistedQueue.items
                            .getOrNull(persistedQueue.mediaItemIndex)
                            ?.id
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    val restoredQueue = persistedQueue.toQueue()
                    withContext(Dispatchers.Main) {
                        playQueue(
                            queue = restoredQueue,
                            playWhenReady = false,
                        )
                    }
                }
                playbackPersistence.read(PERSISTENT_AUTOMIX_FILE, PersistQueue::class.java)
                    ?.let { persistedAutomix ->
                    val items = persistedAutomix.items.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        automixRuntime.restore(
                            restoredItems = items,
                            persistedSeedMediaId = persistedAutomix.automixSeedMediaId,
                            fallbackSeedMediaId = restoredQueueSeedMediaId,
                            restoredAutoAddedMediaIds = persistedAutomix.automixAutoAddedMediaIds,
                        )
                    }
                }
                
                playbackPersistence.read(PERSISTENT_PLAYER_STATE_FILE, PersistPlayerState::class.java)
                    ?.let { playerState ->
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        player.repeatMode = playerState.repeatMode
                        player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume
                        
                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }
                        
                        currentMediaMetadata.value = player.currentMetadata
                        updateNotification()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
            }
        }

    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = Job()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
    }









    fun hasAudioFocusForPlayback(): Boolean {
        return playbackFocusController.hasFocus
    }

    private fun isDeviceMutedNow(): Boolean {
        return player.isDeviceMuted || player.deviceVolume <= 0
    }

    private fun isTogetherGuestSession(): Boolean {
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        return joined?.role is com.nikhil.yt.together.TogetherRole.Guest
    }

    private fun handleDeviceMuteStateChanged() {
        if (!pauseOnDeviceMuteEnabled || isTogetherGuestSession()) {
            wasAutoPausedByDeviceMute = false
            return
        }

        if (isDeviceMutedNow()) {
            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
            }
            return
        }

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            if (!autoStartOnBluetoothEnabled) return

            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            val isAudioDevice = try {
                val majorClass = device.bluetoothClass?.majorDeviceClass
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    majorClass == BluetoothClass.Device.Major.WEARABLE
            } catch (_: SecurityException) {
                true
            }

            if (!isAudioDevice) return

            scope.launch {
                delay(1500)
                handleBluetoothAutoStart()
            }
        }
    }

    private fun handleBluetoothAutoStart() {
        if (isTogetherGuestSession()) return

        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "unregister Bluetooth receiver", error)
        }
        bluetoothReceiverRegistered = false
    }



    private fun skipOnError() {
        val nextWindowIndex = player.nextMediaItemIndex

        if (nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun handleTerminalPlaybackError() {
        val mediaId = player.currentMediaItem?.mediaId
        val decision =
            playbackRecoveryCoordinator.recordTerminalFailure(
                mediaId = mediaId,
                autoSkipEnabled = dataStore.get(AutoSkipNextOnErrorKey, false),
            )

        if (decision.circuitOpenedNow) {
            Timber.tag("MusicService").e(
                "Playback failure circuit opened after %d tracks; queue traversal stopped at id=%s",
                decision.failureCount,
                mediaId,
            )
            Toast.makeText(
                this,
                getString(R.string.error_too_many_failed_tracks),
                Toast.LENGTH_LONG,
            ).show()
        } else if (!decision.mayAutoSkip) {
            Timber.tag("MusicService").w(
                "Playback failure circuit suppressed another skip id=%s count=%d open=%s",
                mediaId,
                decision.failureCount,
                decision.circuitOpen,
            )
        }

        when (decision.action) {
            TerminalPlaybackAction.SKIP -> skipOnError()
            TerminalPlaybackAction.STOP -> stopOnError()
        }
    }

    private fun updateNotification() {
        try {
            val customLayout = listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked == true) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> R.string.repeat_mode_off
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
            )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        runCatching { super.onUpdateNotification(mediaSession, player.isPlaying) }
            .onFailure { reportException(it) }
    }







    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_DISABLED")
                return
            }
            ensureScopesActive()
            scope.launch(SilentHandler) {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        queue.getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideo(dataStore.get(HideVideoKey, false))
                    }

                val targetItem =
                    initialStatus.items.getOrNull(initialStatus.mediaItemIndex)
                        ?: queue.preloadItem?.toMediaItem()

                val meta = targetItem?.metadata
                val trackId =
                    meta?.id?.trim().orEmpty().ifBlank {
                        targetItem?.mediaId?.trim().orEmpty()
                    }
                if (trackId.isBlank()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_NO_TRACK")
                    return@launch
                }

                val track =
                    com.nikhil.yt.together.TogetherTrack(
                        id = trackId,
                        title = meta?.title ?: trackId,
                        artists = meta?.artists?.map { it.name }.orEmpty(),
                        durationSec = meta?.duration ?: -1,
                        thumbnailUrl = meta?.thumbnailUrl,
                    )

                val ops =
                    com.nikhil.yt.together.TogetherGuestPlaybackPlanner.planPlayTrackNow(
                        roomState = joined.roomState,
                        track = track,
                        positionMs = initialStatus.position,
                        playWhenReady = playWhenReady,
                    )

                if (ops.isEmpty()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_BLOCKED")
                    return@launch
                }

                showTogetherNotice(getString(R.string.together_requesting_song_change), key = "GUEST_PLAYQUEUE_REQUEST")
                ops.forEach { op ->
                    when (op) {
                        is com.nikhil.yt.together.TogetherGuestOp.Control -> requestTogetherControl(op.action)
                        is com.nikhil.yt.together.TogetherGuestOp.AddTrack -> requestTogetherAddTrack(op.track, op.mode)
                    }
                }
            }
            return
        }
        ensureScopesActive()
        suppressAutoPlayback = false
        currentQueue = queue
        queueTitle = null
        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        if (!permanentShuffle) {
            player.shuffleModeEnabled = false
        }
        
        clearAutomix()
        automixRuntime.seedMediaId = null
        autoAddedMediaIds.clear()
        queue.preloadItem?.let { preloadItem ->
            player.setMediaItem(preloadItem.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
                }
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size
                    )
                )
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            } else {
                val items = initialStatus.items
                val index = initialStatus.mediaItemIndex

                val windowStart = (index - 20).coerceAtLeast(0)
                val windowEnd = (index + 50).coerceAtMost(items.size)
                
                val initialChunk = items.subList(windowStart, windowEnd)
                val relativeIndex = index - windowStart
                
                player.setMediaItems(
                    initialChunk,
                    if (relativeIndex > 0) relativeIndex else 0,
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }

                if (items.size > initialChunk.size) {
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000)
                            if (!isActive) return@launch

                            if (windowStart > 0) {
                                val startChunk = items.subList(0, windowStart)
                                player.addMediaItems(0, startChunk)
                            }

                            if (windowEnd < items.size) {
                                val endChunk = items.subList(windowEnd, items.size)
                                player.addMediaItems(endChunk)
                            }

                            if (player.shuffleModeEnabled) {
                                applyCurrentFirstShuffleOrder()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to load deferred queue items")
                        }
                    }
                }
            }
        }
    }

    private fun applyCurrentFirstShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffledIndices = IntArray(count) { it }
        shuffledIndices.shuffle()
        val currentPos = shuffledIndices.indexOf(currentIndex)
        if (currentPos >= 0) {
            shuffledIndices[currentPos] = shuffledIndices[0]
        }
        shuffledIndices[0] = currentIndex
        player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    fun startRadioSeamlessly() {
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_DISABLED")
                return
            }
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_UNSUPPORTED")
            return
        }
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(videoId = currentMediaId)
            )
            val initialStatus = withContext(Dispatchers.IO) {
                radioQueue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems = initialStatus.items.filter { item ->
                item.mediaId != currentMediaId
            }
            
            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount
                
                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }
                
                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun getAutomixAlbum(albumId: String) {
        if (!dataStore.get(AutoLoadMoreKey, true) || player.repeatMode != REPEAT_MODE_OFF) return
        val seedAtRequest = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
        automixCoordinator.loadAlbum(
            albumId = albumId,
            expectedSeedMediaId = seedAtRequest,
            currentSeedProvider = {
                player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
            },
        )
    }

    fun getAutomix(playlistId: String) {
        if (!dataStore.get(AutoLoadMoreKey, true) || player.repeatMode != REPEAT_MODE_OFF) return
        val seedAtRequest = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
        automixCoordinator.loadPlaylist(
            playlistId = playlistId,
            expectedSeedMediaId = seedAtRequest,
            currentSeedProvider = {
                player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
            },
        )
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixCoordinator.removeAt(position)
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixCoordinator.removeAt(position)
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixCoordinator.clear()
    }

    private fun refreshAutomixForCurrentMedia() {
        if (!dataStore.get(AutoLoadMoreKey, true)) return
        if (player.repeatMode != REPEAT_MODE_OFF) return
        if (suppressAutoPlayback) return
        if (player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return

        val seedMediaId = player.currentMetadata?.id?.trim()?.ifBlank { null } ?: return
        automixCoordinator.refresh(
            seedMediaId = seedMediaId,
            hideExplicit = dataStore.get(HideExplicitKey, false),
            hideVideo = dataStore.get(HideVideoKey, false),
            queueIdsProvider = {
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            },
            isRelevant = { seed ->
                player.currentMediaItem?.mediaId == seed &&
                    !suppressAutoPlayback &&
                    player.playbackState != STATE_IDLE &&
                    player.mediaItemCount > 0
            },
            noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
            failureMessage = { getString(R.string.error_automix_failed) },
        )
    }

    fun onInfiniteQueueDisabled() {
        automixCoordinator.cancelTransientWork()
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = automixCoordinator.ownedIdsSnapshot()
        if (idsToRemove.isNotEmpty()) {
            for (i in player.mediaItemCount - 1 downTo 0) {
                if (i == currentIndex) continue
                if (player.getMediaItemAt(i).mediaId in idsToRemove) {
                    player.removeMediaItem(i)
                }
            }
        }
        automixCoordinator.clearOwnedIds()
        clearAutomix()
    }

    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata
        if (currentMeta == null) {
            automixError.value = getString(R.string.error_no_song_playing)
            return
        }

        val seedMediaId = currentMeta.id.trim().ifBlank { return }
        automixCoordinator.expandNow(
            seedMediaId = seedMediaId,
            hideExplicit = dataStore.get(HideExplicitKey, false),
            hideVideo = dataStore.get(HideVideoKey, false),
            queueIdsProvider = {
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            },
            isRelevant = { seed ->
                !suppressAutoPlayback &&
                    player.playbackState != STATE_IDLE &&
                    player.mediaItemCount > 0 &&
                    automixRuntime.seedMediaId == seed
            },
            onAddItems = { player.addMediaItems(it) },
            noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
            failureMessage = { getString(R.string.error_automix_failed) },
        )
    }

    fun stopAndClearPlayback() {
        suppressAutoPlayback = true
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        audioResolveCoordinator.cancelAll()
        playbackRecoveryCoordinator.cancelNetworkRecovery()
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        playbackFocusController.abandonFocus()
        closeAudioEffectSession()
        playbackRecoveryCoordinator.resetFailureGuard()
    }

    fun playNext(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    com.nikhil.yt.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.asReversed().forEach { track ->
                requestTogetherAddTrack(track, com.nikhil.yt.together.AddTrackMode.PLAY_NEXT)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            items
        )
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    com.nikhil.yt.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.forEach { track ->
                requestTogetherAddTrack(track, com.nikhil.yt.together.AddTrackMode.ADD_TO_QUEUE)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(items)
        player.prepare()
    }

    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: com.nikhil.yt.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        togetherSessionController.startLanHost(
            port = port,
            displayName = displayName,
            settings = settings,
        )
    }

    private fun togetherOnlineErrorMessage(t: Throwable): String {
        if (t is com.nikhil.yt.together.TogetherOnlineApiException) {
            val code = t.statusCode
            return when {
                code == 404 -> getString(R.string.together_session_not_found)
                code != null && code in 500..599 -> getString(R.string.together_server_error)
                else -> t.message ?: getString(R.string.network_unavailable)
            }
        }
        val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return when (root) {
            is UnknownHostException -> getString(R.string.together_server_unreachable)
            is ConnectException -> getString(R.string.together_server_unreachable)
            is SocketTimeoutException -> getString(R.string.together_connection_timed_out)
            is javax.net.ssl.SSLHandshakeException -> getString(R.string.together_server_unreachable)
            else -> getString(R.string.network_unavailable)
        }
    }

    fun startTogetherOnlineHost(
        displayName: String,
        settings: com.nikhil.yt.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        togetherSessionController.startOnlineHost(
            displayName = displayName,
            settings = settings,
        )
    }

    fun joinTogether(
        rawLink: String,
        displayName: String,
    ) {
        ensureScopesActive()
        togetherSessionController.joinLan(
            rawLink = rawLink,
            displayName = displayName,
        )
    }

    fun joinTogetherOnline(
        code: String,
        displayName: String,
    ) {
        ensureScopesActive()
        togetherSessionController.joinOnline(
            code = code,
            displayName = displayName,
        )
    }

    fun leaveTogether() {
        ensureScopesActive()
        togetherSessionController.leave()
    }

    fun updateTogetherSettings(settings: com.nikhil.yt.together.TogetherRoomSettings) {
        val server = togetherRuntime.server
        val onlineHost = togetherRuntime.onlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.updateSettings(settings)
            onlineHost?.updateSettings(settings)
        }
    }

    fun approveTogetherParticipant(participantId: String, approved: Boolean) {
        val server = togetherRuntime.server
        val onlineHost = togetherRuntime.onlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.approveParticipant(participantId, approved)
            onlineHost?.approveParticipant(participantId, approved)
        }
    }

    fun kickTogetherParticipant(participantId: String, reason: String? = null) {
        val onlineHost = togetherRuntime.onlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.kickParticipant(participantId, reason)
        }
    }

    fun banTogetherParticipant(participantId: String, reason: String? = null) {
        val onlineHost = togetherRuntime.onlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.banParticipant(participantId, reason)
        }
    }

    fun requestTogetherControl(action: com.nikhil.yt.together.ControlAction) {
        val client =
            togetherRuntime.client ?: run {
                showTogetherNotice(getString(R.string.network_unavailable), key = "TOGETHER_CLIENT_MISSING")
                return
            }
        val state = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined ?: return
        if (state.role !is com.nikhil.yt.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToControlPlayback) {
            Timber.tag("Together").i("control blocked locally (disabled) action=${action::class.java.simpleName}")
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_CONTROL_DISABLED_LOCAL")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!togetherGuestControl.registerOutgoing(action, now, togetherRuntime.isOnlineSession)) return

        client.requestControl(state.sessionId, action)
    }

    fun requestTogetherAddTrack(
        track: com.nikhil.yt.together.TogetherTrack,
        mode: com.nikhil.yt.together.AddTrackMode,
    ) {
        val client = togetherRuntime.client ?: return
        val state = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined ?: return
        if (state.role !is com.nikhil.yt.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToAddTracks) {
            Timber.tag("Together").i("add blocked locally (disabled) mode=$mode trackId=${track.id}")
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_ADD_DISABLED_LOCAL")
            return
        }
        client.requestAddTrack(state.sessionId, track, mode)
    }

    private suspend fun handleTogetherHostEvent(
        event: com.nikhil.yt.together.TogetherServerEvent,
        currentSettings: suspend () -> com.nikhil.yt.together.TogetherRoomSettings,
    ) {
        when (event) {
            is com.nikhil.yt.together.TogetherServerEvent.ControlRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToControlPlayback) return
                applyHostControl(event.request.action)
            }

            is com.nikhil.yt.together.TogetherServerEvent.AddTrackRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToAddTracks) return
                applyHostAddTrack(event.request.track, event.request.mode)
            }

            is com.nikhil.yt.together.TogetherServerEvent.Error -> {
                val current = togetherSessionState.value
                if (current is com.nikhil.yt.together.TogetherSessionState.Idle) return
                togetherSessionState.value =
                    com.nikhil.yt.together.TogetherSessionState.Error(
                        message = event.message,
                        recoverable = true,
                    )
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
            }

            else -> Unit
        }
    }

    private suspend fun applyHostControl(action: com.nikhil.yt.together.ControlAction) {
        withContext(Dispatchers.Main) {
            when (action) {
                com.nikhil.yt.together.ControlAction.Play -> {
                    if (!player.playWhenReady) {
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                com.nikhil.yt.together.ControlAction.Pause -> {
                    if (player.playWhenReady) {
                        player.playWhenReady = false
                    }
                }

                is com.nikhil.yt.together.ControlAction.SeekTo -> {
                    player.seekTo(action.positionMs.coerceAtLeast(0L))
                    player.prepare()
                }

                com.nikhil.yt.together.ControlAction.SkipNext -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNext()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                com.nikhil.yt.together.ControlAction.SkipPrevious -> {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPrevious()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                is com.nikhil.yt.together.ControlAction.SeekToTrack -> {
                    val trackId = action.trackId.trim()
                    if (trackId.isNotBlank()) {
                        val idx =
                            player.mediaItems.indexOfFirst {
                                val metaId = it.metadata?.id
                                it.mediaId == trackId || metaId == trackId
                            }
                        if (idx >= 0 && idx < player.mediaItemCount) {
                            player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                            player.prepare()
                        }
                    }
                }

                is com.nikhil.yt.together.ControlAction.SeekToIndex -> {
                    val idx = action.index.coerceAtLeast(0)
                    if (idx < player.mediaItemCount) {
                        player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                        player.prepare()
                    }
                }

                is com.nikhil.yt.together.ControlAction.SetRepeatMode -> {
                    if (player.repeatMode != action.repeatMode) {
                        player.repeatMode = action.repeatMode
                    }
                }

                is com.nikhil.yt.together.ControlAction.SetShuffleEnabled -> {
                    if (player.shuffleModeEnabled != action.shuffleEnabled) {
                        player.shuffleModeEnabled = action.shuffleEnabled
                    }
                }
            }
        }
    }

    private suspend fun applyHostAddTrack(
        track: com.nikhil.yt.together.TogetherTrack,
        mode: com.nikhil.yt.together.AddTrackMode,
    ) {
        val mediaItem = track.toMediaMetadata().toMediaItem()
        withContext(Dispatchers.Main) {
            when (mode) {
                com.nikhil.yt.together.AddTrackMode.PLAY_NEXT -> playNext(listOf(mediaItem))
                com.nikhil.yt.together.AddTrackMode.ADD_TO_QUEUE -> addToQueue(listOf(mediaItem))
            }
        }
    }

    private suspend fun buildTogetherRoomState(
        sessionId: String,
        hostId: String,
    ): com.nikhil.yt.together.TogetherRoomState {
        return withContext(Dispatchers.Main) {
            val tracks =
                player.mediaItems.mapNotNull { it.metadata }.map { meta ->
                    com.nikhil.yt.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }

            val queueHash = com.nikhil.yt.utils.md5(tracks.joinToString(separator = "|") { it.id })

            com.nikhil.yt.together.TogetherRoomState(
                sessionId = sessionId,
                hostId = hostId,
                settings = com.nikhil.yt.together.TogetherRoomSettings(),
                participants = emptyList(),
                queue = tracks,
                queueHash = queueHash,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    private suspend fun applyRemoteRoomState(state: com.nikhil.yt.together.TogetherRoomState) {
        val pid = togetherRuntime.selfParticipantId ?: return
        val now = android.os.SystemClock.elapsedRealtime()

        val reconcileDecision = togetherGuestControl.reconcile(state, now)
        if (reconcileDecision.notifySongChangeFailure) {
            showTogetherNotice(getString(R.string.together_song_change_failed), key = "GUEST_SEEK_TIMEOUT")
        }
        if (!reconcileDecision.applyRemoteState) return

        val lastSentAt = togetherRuntime.lastAppliedRoomStateSentAtElapsedMs
        val sentAt = state.sentAtElapsedRealtimeMs
        if (sentAt > 0L && lastSentAt > 0L && sentAt <= lastSentAt) return

        val offset = if (togetherRuntime.isOnlineSession) 0L else (togetherRuntime.clock?.snapshot()?.estimatedOffsetMs ?: 0L)
        val correctedSentAt = sentAt + offset
        val estimatedOnlineLatency = if (togetherRuntime.isOnlineSession) 1200L else 0L
        val delta = if (togetherRuntime.isOnlineSession) estimatedOnlineLatency else (now - correctedSentAt).coerceAtLeast(0L)
        val targetPos =
            if (state.isPlaying) (state.positionMs + delta).coerceAtLeast(0L) else state.positionMs.coerceAtLeast(0L)

        withContext(Dispatchers.Main) {
            togetherRuntime.beginRemoteApply(android.os.SystemClock.elapsedRealtime())
            try {
                val desiredItems = state.queue.map { it.toMediaMetadata().toMediaItem() }
                val desiredIds = state.queue.map { it.id }
                val desiredHash = state.queueHash
                val localIds = player.mediaItems.mapNotNull { it.metadata?.id ?: it.mediaId }.filter { it.isNotBlank() }
                val localHash = if (localIds.isEmpty()) "" else com.nikhil.yt.utils.md5(localIds.joinToString(separator = "|"))
                val needsRebuild =
                    desiredItems.isNotEmpty() &&
                        (
                            (desiredHash.isNotBlank() && desiredHash != localHash) ||
                                (desiredHash.isBlank() && desiredIds != localIds)
                        )

                if (desiredItems.isNotEmpty() && needsRebuild) {
                    togetherRuntime.lastAppliedQueueHash = desiredHash.ifBlank { localHash }
                    val startIndex = state.currentIndex.coerceIn(0, desiredItems.lastIndex)
                    suppressAutoPlayback = false
                    currentQueue =
                        com.nikhil.yt.playback.queues.ListQueue(
                            title = getString(R.string.music_player),
                            items = desiredItems,
                            startIndex = startIndex,
                            position = targetPos,
                        )
                    queueTitle = null
                    player.setMediaItems(desiredItems, startIndex, targetPos)
                    player.prepare()
                    player.repeatMode = state.repeatMode
                    player.shuffleModeEnabled = state.shuffleEnabled
                    player.playWhenReady = state.isPlaying
                    togetherRuntime.lastRemoteAppliedIndex = startIndex
                } else {
                    val index = state.currentIndex.coerceAtLeast(0)
                    val indexChanged = player.mediaItemCount > 0 && index != player.currentMediaItemIndex
                    val stateChanged =
                        player.repeatMode != state.repeatMode ||
                            player.shuffleModeEnabled != state.shuffleEnabled ||
                            player.playWhenReady != state.isPlaying

                    if (indexChanged) {
                        player.seekTo(index.coerceAtMost(player.mediaItemCount - 1), targetPos)
                        player.prepare()
                        player.playWhenReady = state.isPlaying
                    } else if (stateChanged) {
                        if (player.repeatMode != state.repeatMode) player.repeatMode = state.repeatMode
                        if (player.shuffleModeEnabled != state.shuffleEnabled) player.shuffleModeEnabled = state.shuffleEnabled
                        if (player.playWhenReady != state.isPlaying) {
                            player.playWhenReady = state.isPlaying
                            val drift = kotlin.math.abs(player.currentPosition - targetPos)
                            if (drift > 100) {
                                player.seekTo(targetPos)
                                player.prepare()
                            }
                        }
                    } else {
                        val drift = kotlin.math.abs(player.currentPosition - targetPos)
                        val seekThreshold = if (togetherRuntime.isOnlineSession) 4000L else 2000L
                        val threshold = if (state.isPlaying) seekThreshold else 200L
                        
                        if (drift > threshold) {
                            player.seekTo(targetPos)
                            player.prepare()
                        }
                    }
                    togetherRuntime.lastRemoteAppliedIndex = index
                }
                togetherRuntime.lastRemoteAppliedPlayWhenReady = state.isPlaying
                togetherRuntime.lastAppliedRoomStateSentAtElapsedMs = sentAt

                togetherSessionState.value =
                    com.nikhil.yt.together.TogetherSessionState.Joined(
                        role = com.nikhil.yt.together.TogetherRole.Guest,
                        sessionId = state.sessionId,
                        selfParticipantId = pid,
                        roomState = state,
                    )
            } finally {
                togetherRuntime.finishRemoteApply()
            }
        }
    }



    private suspend fun stopTogetherInternal() {
        togetherGuestControl.reset()
        togetherRuntime.stopConnections()
    }

    private fun com.nikhil.yt.together.TogetherTrack.toMediaMetadata(): com.nikhil.yt.models.MediaMetadata {
        return com.nikhil.yt.models.MediaMetadata(
            id = id,
            title = title,
            artists = artists.map { name -> com.nikhil.yt.models.MediaMetadata.Artist(id = null, name = name) },
            duration = durationSec,
            thumbnailUrl = thumbnailUrl,
            album = null,
            setVideoId = null,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )
    }

    private fun getLocalIpv4Address(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it.isNotBlank() && it != "127.0.0.1" }
        }.getOrNull()
    }

    private fun activeSongMetadata(): com.nikhil.yt.models.MediaMetadata? =
        player.currentMetadata
            ?: currentMediaMetadata.value
            ?: player.currentMediaItem?.metadata

    private fun activeSongId(metadata: com.nikhil.yt.models.MediaMetadata? = activeSongMetadata()): String? =
        metadata?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: player.currentMediaItem
                ?.mediaId
                ?.trim()
                ?.takeIf { it.isNotBlank() }

    private suspend fun ensureSongForMutation(
        mediaId: String,
        metadata: com.nikhil.yt.models.MediaMetadata?,
    ): Song? {
        var current = database.getSongById(mediaId)
        if (current != null) return current

        val sourceMetadata =
            metadata
                ?.takeIf { it.id.trim() == mediaId }
                ?: return null

        database.insert(sourceMetadata)
        current = database.getSongById(mediaId)
        return current
    }

    private fun toggleLibrary() {
        val metadata = activeSongMetadata()
        val mediaId = activeSongId(metadata) ?: return

        ioScope.launch {
            songMutationMutex.withLock {
                database.withTransaction {
                    val current = ensureSongForMutation(mediaId, metadata) ?: return@withTransaction
                    update(current.song.toggleLibrary())
                }
            }
        }
    }

    fun toggleLike(source: String = "service") {
        val metadata = activeSongMetadata()
        val mediaId = activeSongId(metadata)

        Timber.tag("MusicService").i(
            "Toggle like requested id=%s source=%s",
            mediaId,
            source,
        )

        if (mediaId == null) {
            Timber.tag("MusicService").w("Toggle like ignored: no active media id source=%s", source)
            return
        }

        ioScope.launch {
            songMutationMutex.withLock {
                val updatedSong =
                    database.withTransaction {
                        val current = ensureSongForMutation(mediaId, metadata)
                        if (current == null) {
                            Timber.tag("MusicService").w(
                                "Toggle like ignored id=%s: no local row and no usable metadata",
                                mediaId,
                            )
                            return@withTransaction null
                        }

                        val wasLiked = current.song.liked
                        val now = LocalDateTime.now()
                        val updated =
                            current.song.copy(
                                liked = !wasLiked,
                                likedDate = if (!wasLiked) now else null,
                                inLibrary =
                                    if (!wasLiked) {
                                        current.song.inLibrary ?: now
                                    } else {
                                        current.song.inLibrary
                                    },
                            )
                        update(updated)
                        updated
                    } ?: return@withLock

                // Keep one owner for the remote mutation. SongEntity.toggleLike()
                // also calls YouTube directly, so using a pure local copy above
                // prevents duplicate like requests while SyncUtils keeps auth and
                // sync-policy checks in one place.
                syncUtils.likeSong(updatedSong)

                Timber.tag("MusicService").i(
                    "Toggle like applied id=%s liked=%s source=%s",
                    updatedSong.id,
                    updatedSong.liked,
                    source,
                )

                if (dataStore.get(AutoDownloadOnLikeKey, false) && updatedSong.liked) {
                    val downloadRequest =
                        androidx.media3.exoplayer.offline.DownloadRequest
                            .Builder(updatedSong.id, updatedSong.id.toUri())
                            .setCustomCacheKey(updatedSong.id)
                            .setData(updatedSong.title.toByteArray())
                            .build()
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        this@MusicService,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false,
                    )
                }
            }
        }
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun decodeBandLevelsMb(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun encodeBandLevelsMb(levelsMb: List<Int>): String {
        return runCatching { EqualizerJson.json.encodeToString(levelsMb) }.getOrNull().orEmpty()
    }

    private fun readEqSettingsFromPrefs(prefs: Preferences): EqSettings {
        val levels = decodeBandLevelsMb(prefs[EqualizerBandLevelsMbKey])
        return EqSettings(
            enabled = prefs[EqualizerEnabledKey] ?: false,
            bandLevelsMb = levels,
            outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
            outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
            bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
            bassBoostStrength = (prefs[EqualizerBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
            virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
            virtualizerStrength = (prefs[EqualizerVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
        )
    }

    fun applyEqFlatPreset() {
        ioScope.launch {
            val bandCount = audioEffectsController.currentBandCount()
            val encoded = encodeBandLevelsMb(List(bandCount.coerceAtLeast(0)) { 0 })
            dataStore.edit { prefs ->
                prefs[EqualizerEnabledKey] = true
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = "flat"
            }
        }
    }

    fun applySystemEqPreset(presetIndex: Int) {
        scope.launch {
            val levels =
                audioEffectsController.applySystemPreset(
                    sessionId = player.audioSessionId,
                    presetIndex = presetIndex,
                ) ?: return@launch

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch

            ioScope.launch {
                dataStore.edit { prefs ->
                    prefs[EqualizerEnabledKey] = true
                    prefs[EqualizerBandLevelsMbKey] = encoded
                    prefs[EqualizerSelectedProfileIdKey] = "system:$presetIndex"
                }
            }
        }
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        val sessionId = player.audioSessionId
        if (sessionId <= 0) return
        isAudioEffectSessionOpened = true
        openedAudioSessionId = sessionId
        audioEffectsController.ensure(sessionId)
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        val sessionId = openedAudioSessionId ?: player.audioSessionId
        openedAudioSessionId = null
        audioEffectsController.release()
        if (sessionId <= 0) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)

        streamRetryJob?.cancel()
        streamRetryJob = null
        playbackRecoveryCoordinator.cancelNetworkRecovery()
        audioResolveStability.onSelectionChanged()
        songMetadataRecoveryCoordinator.cancelExcept(mediaItem?.mediaId)
        prefetchUpcomingAudio()

        val transitionedMediaId =
            mediaItem?.mediaId
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val videoState = videoPlaybackState.value
        val previousCanonicalId =
            videoOriginalMediaId
                ?: videoState.mediaId

        val canonicalChanged =
            transitionedMediaId != null &&
                previousCanonicalId != null &&
                transitionedMediaId != previousCanonicalId

        /*
         * FINAL CAPSULE VIDEO POLICY
         *
         * VIDEO belongs only to the track for which the user explicitly
         * enabled it. A real queue transition always starts the new song in
         * normal AUDIO. Seeking/re-preparing the same song does not reset it.
         */
        if (
            canonicalChanged &&
            !isCurrentCapsuleVideoItem()
        ) {
            videoResolveCoordinator.cancel()

            /*
             * If the old queue slot was temporarily replaced by a
             * capsule-video MediaItem, put the original AUDIO item back so
             * pressing Previous later cannot silently re-enter VIDEO.
             */
            val originalVideoTrackItem = videoOriginalMediaItem
            if (
                originalVideoTrackItem != null &&
                previousCanonicalId != null
            ) {
                val staleVideoIndex =
                    (0 until player.mediaItemCount).firstOrNull { index ->
                        val queuedItem = player.getMediaItemAt(index)
                        queuedItem.mediaId == previousCanonicalId &&
                            queuedItem.localConfiguration
                                ?.uri
                                ?.scheme
                                ?.equals(
                                    CAPSULE_VIDEO_SCHEME,
                                    ignoreCase = true,
                                ) == true
                    }

                if (staleVideoIndex != null) {
                    runCatching {
                        player.replaceMediaItem(
                            staleVideoIndex,
                            originalVideoTrackItem,
                        )
                    }.onFailure { throwable ->
                        Timber.tag("CapsuleVideo").w(
                            throwable,
                            "Could not restore the previous AUDIO queue slot",
                        )
                    }
                }
            }

            videoOriginalMediaItem = null
            videoOriginalMediaId = null
            videoSuspendedForScreenOff = false

            videoPlaybackState.value =
                CapsuleVideoPlaybackState(
                    preferredMode = CapsulePlaybackMode.AUDIO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.IDLE,
                    mediaId = transitionedMediaId,
                    videoId = null,
                    qualityLabel = null,
                    width = null,
                    height = null,
                    message = null,
                )
        }


    crossfadeAudio?.onMediaItemTransition(mediaItem, reason)

    val currentIndex = player.currentMediaItemIndex
    val queue = player.mediaItems.mapNotNull { it.metadata }
    if (queue.isNotEmpty()) {
        lyricsPreloadManager?.onSongChanged(currentIndex, queue)
    }

    val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
    if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest &&
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    ) {
        if (!joined.roomState.settings.allowGuestsToControlPlayback) {
            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val isEcho =
            isTogetherApplyingRemote() ||
                (now < togetherRuntime.suppressEchoUntilElapsedMs && togetherRuntime.lastRemoteAppliedIndex == index)
        if (!isEcho) {
            val trackId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
            requestTogetherControl(
                if (trackId.isBlank()) {
                    com.nikhil.yt.together.ControlAction.SeekToIndex(
                        index = index,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                    )
                } else {
                    com.nikhil.yt.together.ControlAction.SeekToTrack(
                        trackId = trackId,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                    )
                },
            )
        }
    }

    val timelineEmpty = player.currentTimeline.isEmpty || player.mediaItemCount == 0 || player.currentMediaItem == null
    currentMediaMetadata.value = if (timelineEmpty) null else (mediaItem?.metadata ?: player.currentMetadata)

    scrobbleCoordinator.onSongStop()

    if (!timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        val isNearEndWithoutPaging =
            player.mediaItemCount - player.currentMediaItemIndex <= 3 && !currentQueue.hasNextPage()

        if (!isNearEndWithoutPaging) {
            val force =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

            val currentId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
            if (force || (currentId.isNotBlank() && automixRuntime.seedMediaId != currentId)) {
                refreshAutomixForCurrentMedia()
            }
        }
    }

    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
        currentQueue.hasNextPage() &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        scope.launch(SilentHandler) {
            val mediaItems =
                currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            if (player.playbackState != STATE_IDLE) {
                player.addMediaItems(mediaItems.drop(1))
            } else {
                discordPresenceOwner.stop()
            }
        }
    }
    
    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.mediaItemCount - player.currentMediaItemIndex <= 3 &&
        !currentQueue.hasNextPage()
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val currentMediaMetadata = player.currentMetadata
            val currentMediaId = currentMediaMetadata?.id?.trim().orEmpty()
            val existingSeed = automixRuntime.seedMediaId?.trim().orEmpty()
            val existingAutomix =
                if (currentMediaId.isNotBlank() && existingSeed == currentMediaId) {
                    automixItems.value
                } else {
                    if (automixItems.value.isNotEmpty()) {
                        clearAutomix()
                    }
                    emptyList()
                }
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId !in queueIds }
                if (filteredAutomix.isNotEmpty()) {
                    player.addMediaItems(filteredAutomix)
                    filteredAutomix.forEach { autoAddedMediaIds.add(it.mediaId) }
                }
                clearAutomix()
            } else {
                if (currentMediaMetadata != null) {
                    refreshAutomixForCurrentMedia()
                }
            }
        }
    }

    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
        scrobbleCoordinator.onSongStart(player.currentMetadata, duration = player.duration)
    }

    playbackPersistence.scheduleQueueSave()
    discordPresenceOwner.ensure()
}

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
    super.onPlaybackStateChanged(playbackState)

    val activeMediaId = player.currentMediaItem?.mediaId
    playbackRecoveryCoordinator.onPlaybackActivity(
        mediaId = activeMediaId,
        ready = playbackState == Player.STATE_READY,
        playing = player.isPlaying,
    )

    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
        crossfadeAudio?.stop(resetMainFade = true)
        scrobbleCoordinator.onSongStop()
    }

    if (!suppressAutoPlayback &&
        playbackState == Player.STATE_ENDED &&
        dataStore.get(AutoLoadMoreKey, true) &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.currentMediaItem != null
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val lastMediaMetadata = player.currentMetadata
            val existingAutomix = automixItems.value
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId != lastMediaMetadata?.id }
                if (filteredAutomix.isNotEmpty()) {
                    automixCoordinator.clearOwnedIds()
                    player.setMediaItems(filteredAutomix, 0, 0)
                    player.prepare()
                    player.play()
                    automixCoordinator.markAutoAdded(filteredAutomix)
                }
                clearAutomix()
            } else if (lastMediaMetadata != null) {
                val hideExplicit = dataStore.get(HideExplicitKey, false)
                val hideVideo = dataStore.get(HideVideoKey, false)
                automixCoordinator.recoverAfterQueueEnded(
                    seedMediaId = lastMediaMetadata.id,
                    hideExplicit = hideExplicit,
                    hideVideo = hideVideo,
                    isBeforeApplyRelevant = {
                        !suppressAutoPlayback && player.playbackState != STATE_IDLE && player.mediaItemCount > 0
                    },
                    isAfterApplyRelevant = {
                        !suppressAutoPlayback && player.playbackState != STATE_IDLE
                    },
                    onReplaceQueue = { radioItems ->
                        player.setMediaItems(radioItems, 0, 0)
                        player.prepare()
                        player.play()
                    },
                    noSimilarSongsMessage = { getString(R.string.error_no_similar_songs) },
                    failureMessage = { getString(R.string.error_automix_failed) },
                )
            }
        }
    }

    discordPresenceOwner.ensure()
    playbackPresenceCoordinator.requestImmediateUpdate()
}

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        val activeMediaId = player.currentMediaItem?.mediaId
        playbackRecoveryCoordinator.onPlaybackActivity(
            mediaId = activeMediaId,
            ready = player.playbackState == Player.STATE_READY,
            playing = isPlaying,
        )
    }




    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(EVENT_POSITION_DISCONTINUITY)) {
            playbackPositionGeneration.markDiscontinuity()
        }
    val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
    if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest &&
        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    ) {
        if (!joined.roomState.settings.allowGuestsToControlPlayback) {
            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            val playWhenReady = this.player.playWhenReady
            val isEcho =
                isTogetherApplyingRemote() ||
                    (now < togetherRuntime.suppressEchoUntilElapsedMs &&
                        togetherRuntime.lastRemoteAppliedPlayWhenReady != null &&
                        togetherRuntime.lastRemoteAppliedPlayWhenReady == playWhenReady)
            if (!isEcho) {
                val action =
                    if (playWhenReady) {
                        com.nikhil.yt.together.ControlAction.Play
                    } else {
                        com.nikhil.yt.together.ControlAction.Pause
                    }
                requestTogetherControl(action)
            }
        }
    }
    if (events.contains(Player.EVENT_DEVICE_VOLUME_CHANGED)) {
        handleDeviceMuteStateChanged()
    }
    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && isDeviceMutedNow() && this.player.playWhenReady) {
        wasAutoPausedByDeviceMute = false
    }
    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
        (this.player.playbackState == Player.STATE_IDLE || this.player.playbackState == Player.STATE_ENDED)
    ) {
        wasAutoPausedByDeviceMute = false
    }
    if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
        val newSessionId = this.player.audioSessionId
        val oldSessionId = openedAudioSessionId
        if (isAudioEffectSessionOpened && newSessionId > 0 && oldSessionId != null && oldSessionId > 0 && oldSessionId != newSessionId) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                },
            )
            openedAudioSessionId = newSessionId
            audioEffectsController.ensure(newSessionId)
            sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                },
            )
        }
    }
    if (events.containsAny(EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
        playbackPersistence.scheduleQueueSave()
    } else if (events.contains(EVENT_POSITION_DISCONTINUITY)) {
        playbackPersistence.schedulePlayerStateSave(syncToDisk = true)
    }
    if (events.containsAny(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED
        )
    ) {
        val isBufferingOrReady =
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
        if (isBufferingOrReady && player.playWhenReady) {
            val focusGranted = playbackFocusController.requestFocus()
            if (focusGranted) openAudioEffectSession()
        } else {
            closeAudioEffectSession()
        }
    }

       if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            playbackPresenceCoordinator.requestImmediateUpdate()
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentMediaMetadata.value = player.currentMetadata
            }
            val currentMediaId = player.currentMediaItem?.mediaId
            val currentMetadata = player.currentMetadata
            val currentPosition = player.currentPosition
            val isPlaying = player.isPlaying

            updateVeluneWidgetState(
                context = this@MusicService,
                title = currentMetadata?.title ?: "Not Playing",
                artist = currentMetadata?.artists?.joinToString(", ") { it.name } ?: "Velune",
                isPlaying = isPlaying,
                thumbnailUrl = currentMetadata?.thumbnailUrl
            )





            playbackPresenceCoordinator.requestImmediateUpdate()
        }

   if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
        playbackPersistence.updateProgressCheckpoint(player.isPlaying)
        discordPresenceOwner.ensure()
        scrobbleCoordinator.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
    } else if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
        discordPresenceOwner.ensure()
    } else {
        discordPresenceOwner.ensure()
    }
  }


    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
                    return
                }
                requestTogetherControl(
                    com.nikhil.yt.together.ControlAction.SetShuffleEnabled(
                        shuffleEnabled = shuffleModeEnabled,
                    ),
                )
            }
            return
        }
        if (shuffleModeEnabled) {
            applyCurrentFirstShuffleOrder()
        }

        playbackPersistence.scheduleQueueSave()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        val joined = togetherSessionState.value as? com.nikhil.yt.together.TogetherSessionState.Joined
        if (joined?.role is com.nikhil.yt.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
                    return
                }
                requestTogetherControl(
                    com.nikhil.yt.together.ControlAction.SetRepeatMode(
                        repeatMode = repeatMode,
                    ),
                )
            }
            return
        }
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        playbackPersistence.schedulePlayerStateSave(syncToDisk = true)
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (tracks.groups.isEmpty() || !player.isAudioOffloadRequested()) return
        if (player.currentAudioOffloadAvailability() != CapsuleAudioOffloadAvailability.UNSUPPORTED) return

        // The route or selected format changed after the user enabled offload.
        // Keep runtime state and persisted UI state honest: unsupported means OFF.
        player.setOffloadEnabled(false)
        scope.launch(Dispatchers.IO) {
            dataStore.edit { preferences ->
                if (preferences[AudioOffload] == true) {
                    preferences[AudioOffload] = false
                }
            }
        }
        Timber.tag("AudioOffload").i(
            "Disabled audio offload after current format/output became unsupported",
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        if (isCurrentCapsuleVideoItem()) {
            Timber.tag("CapsuleVideo").w(error, "Video mode failed; restoring original audio item")
            restoreAudioFromVideoFailure(
                error.message ?: error.cause?.message ?: "Video stream unavailable",
            )
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        val httpStatusCode = error.httpStatusCodeOrNull()

        if (generateSequence<Throwable>(error) { it.cause }.take(8).any { it is AudioFormatChangedException }) {
            if (currentMediaId != null && playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId) != null) {
                recreateAudioSources()
            } else player.pause()
            return
        }

        if (
            currentMediaId != null &&
            CapsuleAudioEngine.isBotDetectionException(error)
        ) {
            /*
             * An explicit bot-check is not a reason to cycle through more
             * identities. Open the AUDIO breaker and stop this attempt.
             */
            CapsuleAudioEngine.markBotDetectionFailure(
                error.message ?: error.cause?.message,
            )
            CapsuleAudioEngine.invalidateCachedStreamUrls(currentMediaId)
            playbackUrlCache.remove(currentMediaId)
            audioResolveCoordinator.cancelAll()
            streamRetryJob?.cancel()
            playbackRecoveryCoordinator.cancelNetworkRecovery()

            Timber.tag("MusicService").w(
                "YouTube bot-check for $currentMediaId — AUDIO requests cooling down",
            )

            player.pause()
            return
        }

        if (httpStatusCode == 429 || CapsuleAudioEngine.isRateLimitedException(error) ||
            CapsuleAudioEngine.playbackBlockedExceptionOrNull() != null
        ) {
            if (httpStatusCode == 429 || CapsuleAudioEngine.isRateLimitedException(error)) {
                CapsuleAudioEngine.markRateLimitedFailure()
            }
            streamRetryJob?.cancel()
            playbackRecoveryCoordinator.cancelNetworkRecovery()
            audioResolveCoordinator.cancelAll()
            currentMediaId?.let(playbackUrlCache::remove)
            player.pause()
            return
        }

        if (currentMediaId != null && error.isNoPlayableStreamFailure()) {
            val claimed = playbackRecoveryCoordinator.claimNoPlayableFreshResolve(currentMediaId)
            val retryDelay = if (claimed) playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId) else null
            if (retryDelay != null && CapsuleAudioEngine.playbackBlockedExceptionOrNull() == null) {
                // Clear only song-local extraction state. Keep the user's selected
                // client/profile and every global anti-bot/rate-limit guard intact.
                CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
                CapsuleAudioEngine.invalidateCachedStreamUrls(currentMediaId)
                audioResolveCoordinator.cancelMedia(currentMediaId) {
                    playbackUrlCache.remove(currentMediaId)
                }
                Timber.tag(CAPSULE_RESOLVE_TAG).w(
                    "No playable stream id=%s; scheduling one clean same-policy resolve",
                    currentMediaId,
                )
                scheduleStreamRefreshRetry(
                    mediaId = currentMediaId,
                    refreshCipherConfig = false,
                    retryReason = "no playable stream",
                    retryDelayMs = retryDelay,
                )
                return
            }

            Timber.tag(CAPSULE_RESOLVE_TAG).w(
                "No playable stream id=%s; bounded fresh-resolve retry unavailable",
                currentMediaId,
            )
            handleTerminalPlaybackError()
            return
        }

        if (!isNetworkConnected.value || error.isTransientNetworkFailure()) {
            playbackRecoveryCoordinator.recoverFromNetworkError()
            return
        }

        val shouldAttemptStreamRefresh =
            currentMediaId != null && (
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    httpStatusCode in setOf(403, 404, 410, 416, 429, 500, 502, 503)
                )

        if (currentMediaId != null && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scope.launch(Dispatchers.IO) {
                runCatching { downloadCache.removeResource(currentMediaId) }
                runCatching { AudioCacheIdentity.remove(playerCache, currentMediaId) }
            }
        }

        if (shouldAttemptStreamRefresh && currentMediaId != null) {
            val retryDelay = playbackRecoveryCoordinator.nextRetryDelayMs(currentMediaId)
            if (
                retryDelay == null ||
                !shouldRetryRejectedSignedUrl(httpStatusCode, retryDelay)
            ) {
                handleTerminalPlaybackError()
                return
            }
            // A rejected/expired URL does not mean the selected client is broken.
            // Keep the same client and identity, but do not burn through fresh
            // generations in a 250 ms loop. After a second signed-URL rejection,
            // refresh the same visitor-bound streaming session once before the
            // final bounded fresh resolve.
            CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
            audioResolveCoordinator.cancelMedia(currentMediaId) {
                playbackUrlCache.remove(currentMediaId)
            }
            scheduleStreamRefreshRetry(
                mediaId = currentMediaId,
                refreshCipherConfig =
                    shouldRefreshStreamSessionAfterSignedUrlRejection(
                        httpStatusCode = httpStatusCode,
                        budgetDelayMs = retryDelay,
                    ),
                retryReason = "http=$httpStatusCode code=${error.errorCode}",
                retryDelayMs = signedUrlRefreshDelayMs(httpStatusCode, retryDelay),
            )
            return
        }

        val skipSilenceCurrentlyEnabled = dataStore.get(SkipSilenceKey, false)
        val causeText = (error.cause?.stackTraceToString() ?: error.stackTraceToString()).lowercase()
        val looksLikeSilenceProcessor = skipSilenceCurrentlyEnabled && (
            "silenceskippingaudioprocessor" in causeText || "silence" in causeText
        )

        if (looksLikeSilenceProcessor) {
            scope.launch {
                try {
                    dataStore.edit { settings ->
                        settings[SkipSilenceKey] = false
                    }
                    player.skipSilenceEnabled = false
                    val currentPos = player.currentPosition
                    val targetPos = min(currentPos + 1500L, if (player.duration > 0) player.duration - 1000L else currentPos + 1500L)
                    player.seekTo(targetPos)
                    player.prepare()
                    player.play()
                    return@launch
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Timber.tag("MusicService").e(t, "failed to recover from silence-skipper error")
                }
                handleTerminalPlaybackError()
            }

            return
        }
        handleTerminalPlaybackError()
    }



    private fun createCacheDataSource(): DataSource.Factory {
        val audioHttpClient =
            mediaOkHttpClient
                .newBuilder()
                // Safe transport-level reconnect for an already-resolved CDN GET.
                // No player/InnerTube request or client rotation happens here.
                .retryOnConnectionFailure(true)
                .addInterceptor(CapsuleAudioRequestInterceptor(guardStreams = true))
                .addNetworkInterceptor(AudioCdnConnectionDiagnosticInterceptor())
                .build()
        val networkUpstream =
            AudioNetworkDiagnosticDataSource.Factory(
                upstreamFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(audioHttpClient)),
                beforeNetworkOpen = ::awaitAudioNetworkOpenPermit,
            )
        val streaming = CacheDataSource.Factory().setCache(playerCache)
            .setUpstreamDataSourceFactory(networkUpstream)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
        val offline = CacheDataSource.Factory().setCache(downloadCache).setCacheWriteDataSinkFactory(null)
        return DataSource.Factory { AudioCacheDataSource(streaming.createDataSource(), offline.createDataSource()) }
    }

    private fun createVideoCacheDataSource(): CacheDataSource.Factory {
        val videoHttpClient =
            mediaOkHttpClient
                .newBuilder()
                .retryOnConnectionFailure(false)
                .addInterceptor(CapsuleVideoStreamInterceptor())
                .build()

        return CacheDataSource
            .Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(
                    this,
                    OkHttpDataSource.Factory(videoHttpClient),
                ),
            )
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val routedCacheFactory =
            CapsuleCacheRoutingDataSource.Factory(
                audioFactory = createCacheDataSource(),
                videoFactory = createVideoCacheDataSource(),
            )

        return DataSource.Factory {
            val contract = AudioStreamContract()
            ResolvingDataSource(routedCacheFactory.createDataSource()) { dataSpec ->
                val splitStreamKey =
                    dataSpec.key
                        ?.takeIf { it.startsWith(CAPSULE_VIDEO_STREAM_CACHE_PREFIX) }
                        ?.removePrefix(CAPSULE_VIDEO_STREAM_CACHE_PREFIX)

                if (!splitStreamKey.isNullOrBlank()) {
                    val parts = splitStreamKey.split(':', limit = 3)
                    val kind = parts.getOrNull(0)
                    val splitVideoId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (splitVideoId != null) {
                        val resolved = YouTubeVideoResolver.peekResolved(splitVideoId)
                            ?: throw PlaybackException(
                                "Cached video streams expired",
                                null,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                            )
                        val streamUrl =
                            when (kind) {
                                "audio" -> resolved.audioStreamUrl
                                else -> resolved.videoStreamUrl
                            }
                                ?: throw PlaybackException(
                                    "Requested video stream is unavailable",
                                    null,
                                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                                )
                        return@ResolvingDataSource dataSpec.withUri(streamUrl.toUri())
                    }
                }

                val videoKey =
                    dataSpec.key
                        ?.takeIf { it.startsWith(CAPSULE_VIDEO_CACHE_PREFIX) }
                val videoId =
                    videoKey
                        ?.removePrefix(CAPSULE_VIDEO_CACHE_PREFIX)
                        ?.substringBefore(':')
                        ?.takeIf { it.isNotBlank() }
                        ?: dataSpec.uri
                            .takeIf { it.scheme.equals(CAPSULE_VIDEO_SCHEME, ignoreCase = true) }
                            ?.lastPathSegment
                            ?.takeIf { it.isNotBlank() }

                if (videoId != null) {
                    val resolvedVideo =
                        runBlocking(Dispatchers.IO) {
                            runCatching {
                                withTimeout(VIDEO_RESOLVE_TIMEOUT_MS) {
                                    YouTubeVideoResolver.resolveMuxed(videoId, capsuleVideoQuality)
                                }
                            }.getOrElse { Result.failure(it) }
                        }.getOrElse { throwable ->
                            videoPlaybackState.value =
                                videoPlaybackState.value.copy(
                                    preferredMode = CapsulePlaybackMode.AUDIO,
                                    mode = CapsulePlaybackMode.AUDIO,
                                    phase = CapsuleVideoPhase.REQUEST_ERROR,
                                    videoId = videoId,
                                    message = "VIDEO temporarily unavailable — continuing with audio",
                                )
                            throw PlaybackException(
                                throwable.message ?: "Video stream unavailable",
                                throwable,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                            )
                        }

                    videoPlaybackState.value =
                        videoPlaybackState.value.copy(
                            preferredMode = CapsulePlaybackMode.VIDEO,
                            mode = CapsulePlaybackMode.VIDEO,
                            phase = CapsuleVideoPhase.PLAYING,
                            videoId = videoId,
                            qualityLabel = resolvedVideo.qualityLabel,
                            width = resolvedVideo.format.width,
                            height = resolvedVideo.format.height,
                            message = null,
                        )

                    return@ResolvingDataSource dataSpec.withUri(resolvedVideo.streamUrl.toUri())
                }

                val mediaId = dataSpec.key ?: error("No media id")
                // Only a complete legacy file can be trusted without resolving its byte format.
                val legacyLength = runBlocking(Dispatchers.IO) { database.format(mediaId).first()?.contentLength }
                val downloadedKey = AudioCacheIdentity.completeKey(downloadCache, mediaId, legacyLength)
                val completeKey = downloadedKey ?: AudioCacheIdentity.completeKey(playerCache, mediaId, legacyLength)
                if (completeKey != null) {
                    contract.bind(completeKey)
                    songMetadataRecoveryCoordinator.schedule(mediaId)
                    return@ResolvingDataSource dataSpec.buildUpon().setKey(completeKey)
                        .setCustomData(if (downloadedKey != null) AudioCacheSource.DOWNLOAD else AudioCacheSource.PLAYER)
                        .build()
                }

                playbackUrlCache.get(mediaId)?.let { cached ->
                    songMetadataRecoveryCoordinator.schedule(mediaId, cached)
                    return@ResolvingDataSource resolvedAudioDataSpec(
                        dataSpec = dataSpec,
                        playback = cached,
                        contract = contract,
                        source = AudioCdnOpenSource.CACHED,
                    )
                }

                /*
                 * Await the shared resolve rather than starting one here. The work
                 * itself lives in ioScope, so every caller for this track shares
                 * the same job. A media transition keeps jobs for the current and
                 * next track, while older abandoned prefetches are cancelled
                 * to avoid request bursts during rapid skipping.
                 *
                 * InnerTubeX owns an 18-second engine budget. This 20-second
                 * ceiling only catches a stuck job outside that engine.
                 */
                val loaderWaitStartedAt = System.currentTimeMillis()
                val alreadyRunning = audioResolveCoordinator.hasInFlight(mediaId)

                val playbackData = runBlocking {
                    runCatching {
                        withTimeout(AUDIO_RESOLVE_TIMEOUT_MS) {
                            CapsuleAudioEngine.prioritizePlayback(mediaId)
                            audioResolveJob(mediaId).await()
                        }
                    }.getOrElse { failure ->
                        val waitedMs = System.currentTimeMillis() - loaderWaitStartedAt
                        if (failure is TimeoutCancellationException) {
                            Timber.tag(CAPSULE_RESOLVE_TAG).w(
                                failure,
                                "loader ceiling timeout id=%s waitedMs=%d budgetMs=%d",
                                mediaId,
                                waitedMs,
                                AUDIO_RESOLVE_TIMEOUT_MS,
                            )
                        } else if (failure is kotlinx.coroutines.CancellationException) {
                            Timber.tag(CAPSULE_RESOLVE_TAG).d(
                                "loader cancelled id=%s waitedMs=%d",
                                mediaId,
                                waitedMs,
                            )
                        } else {
                            Timber.tag(CAPSULE_RESOLVE_TAG).w(
                                "loader gave up id=%s waitedMs=%d cause=%s",
                                mediaId,
                                waitedMs,
                                failure::class.java.simpleName,
                            )
                        }
                        Result.failure(failure)
                    }
                }.also {
                    val waited = System.currentTimeMillis() - loaderWaitStartedAt
                    Timber.tag(CAPSULE_RESOLVE_TAG).i(
                        "loader blocked id=%s waitedMs=%d joinedExisting=%s",
                        mediaId,
                        waited,
                        alreadyRunning,
                    )
                }.getOrElse { throwable ->
                    when (throwable) {
                        is PlaybackException -> throw throwable

                        is java.net.ConnectException, is java.net.UnknownHostException -> {
                            throw PlaybackException(
                                getString(R.string.error_no_internet),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            )
                        }

                        is TimeoutCancellationException,
                        is java.net.SocketTimeoutException,
                        -> {
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )
                        }

                        else -> throw PlaybackException(
                            getString(R.string.error_unknown),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR
                        )
                    }
                }

                songMetadataRecoveryCoordinator.schedule(mediaId, playbackData)
                return@ResolvingDataSource resolvedAudioDataSpec(
                    dataSpec = dataSpec,
                    playback = playbackData,
                    contract = contract,
                    source =
                        if (alreadyRunning) AudioCdnOpenSource.JOINED_INFLIGHT
                        else AudioCdnOpenSource.ON_DEMAND,
                )
            }
        }
    }

    private fun resolvedAudioDataSpec(
        dataSpec: androidx.media3.datasource.DataSpec,
        playback: CapsuleAudioEngine.PlaybackData,
        contract: AudioStreamContract,
        source: AudioCdnOpenSource,
    ): androidx.media3.datasource.DataSpec {
        val mediaId = requireNotNull(dataSpec.key)
        val key = AudioCacheIdentity.key(mediaId, playback)
        contract.bind(key)
        AudioCacheIdentity.setLength(playerCache, key, playback.format.contentLength)
        return dataSpec.buildUpon()
            .setKey(key)
            .setUri(playback.streamUrl.toUri())
            .setCustomData(
                AudioCdnOpenContext(
                    mediaId = mediaId,
                    resolvedAtElapsedMs = playback.resolvedAtElapsedMs,
                    source = source,
                    streamClient = playback.streamClient,
                ),
            )
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + playback.streamHeaders)
            .build()
    }

    private suspend fun cacheResolvedPlayback(
        mediaId: String,
        playback: CapsuleAudioEngine.PlaybackData,
        generation: Long,
        selection: AudioPlaybackContext,
    ) {
        val format = playback.format
        var loudness = TrackLoudness(
            playback.audioConfig?.loudnessDb ?: format.loudnessDb,
            playback.audioConfig?.perceptualLoudnessDb ?: format.perceptualLoudnessDb,
        )
        if (!audioResolveCoordinator.isPolicyGenerationCurrent(generation)) return
        try {
            val stored = database.format(mediaId).first()
            loudness = TrackLoudness(
                loudness.loudnessDb ?: stored?.loudnessDb,
                loudness.perceptualLoudnessDb ?: stored?.perceptualLoudnessDb,
            )
            database.upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.substringBefore(';'),
                    codecs = format.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength ?: C.LENGTH_UNSET.toLong(),
                    loudnessDb = loudness.loudnessDb,
                    perceptualLoudnessDb = loudness.perceptualLoudnessDb,
                    playbackUrl = playback.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportRecoverableException("MusicService", "store resolved audio metadata", failure)
        }
        val resolveContext = currentCoroutineContext()
        audioResolveCoordinator.publishIfCurrent(generation) {
            resolveContext.ensureActive()
            publishResolvedLoudness(mediaId, loudness)
            playbackUrlCache.put(mediaId, playback, selection)
        }
    }

    private fun publishResolvedLoudness(
        mediaId: String,
        loudness: TrackLoudness,
    ) {
        freshlyResolvedLoudness[mediaId] = loudness

        if (freshlyResolvedLoudness.size > 128) {
            freshlyResolvedLoudness.keys
                .asSequence()
                .filterNot { it == mediaId }
                .take(freshlyResolvedLoudness.size - 96)
                .forEach(freshlyResolvedLoudness::remove)
        }

        freshlyResolvedLoudnessVersion.update { it + 1L }
    }

    fun setCapsulePlaybackMode(mode: CapsulePlaybackMode) {
        when (mode) {
            CapsulePlaybackMode.AUDIO -> {
                videoPlaybackState.value =
                    videoPlaybackState.value.copy(
                        preferredMode = CapsulePlaybackMode.AUDIO,
                    )
                leaveCapsuleVideoMode()
            }
            CapsulePlaybackMode.VIDEO -> {
                videoPlaybackState.value =
                    videoPlaybackState.value.copy(
                        preferredMode = CapsulePlaybackMode.VIDEO,
                    )
                enterCapsuleVideoMode()
            }
        }
    }

    private fun enterCapsuleVideoMode() {
        if (!canUseCapsuleVideoForScreen()) {
            videoSuspendedForScreenOff = true
            val currentId = player.currentMediaItem?.mediaId
            videoPlaybackState.value =
                videoPlaybackState.value.copy(
                    preferredMode = CapsulePlaybackMode.VIDEO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.IDLE,
                    mediaId = currentId,
                    message = null,
                )
            return
        }

        if (CapsuleVideoRequestGuard.isBlocked()) {
            videoPlaybackState.value =
                videoPlaybackState.value.copy(
                    preferredMode = CapsulePlaybackMode.VIDEO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.REQUEST_ERROR,
                    mediaId = player.currentMediaItem?.mediaId,
                    message = "Video requests temporarily paused",
                )
            return
        }

        val currentItem = player.currentMediaItem ?: return
        if (isCurrentCapsuleVideoItem()) return

        val canonicalMediaId = currentItem.mediaId.trim()
        if (canonicalMediaId.isBlank()) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return

        val sourceMetadata = currentItem.metadata ?: player.currentMetadata
        val sourceTitle =
            sourceMetadata?.title
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: currentItem.mediaMetadata.title
                    ?.toString()
                    ?.trim()
                    .orEmpty()
        val sourceArtists =
            sourceMetadata
                ?.artists
                ?.map { it.name.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        val sourceDurationSeconds =
            sourceMetadata
                ?.duration
                ?.takeIf { it > 0 }
                ?: player.duration
                    .takeIf { it > 0L && it != C.TIME_UNSET }
                    ?.div(1000L)
                    ?.toInt()

        videoResolveCoordinator.cancel()
        videoOriginalMediaItem = currentItem
        videoOriginalMediaId = canonicalMediaId

        videoPlaybackState.value =
            CapsuleVideoPlaybackState(
                preferredMode = CapsulePlaybackMode.VIDEO,
                mode = CapsulePlaybackMode.AUDIO,
                phase = CapsuleVideoPhase.RESOLVING,
                mediaId = canonicalMediaId,
                videoId = null,
                message = null,
            )

        videoResolveCoordinator.resolve(
            request =
                CapsuleVideoResolveRequest(
                    sourceMediaId = canonicalMediaId,
                    title = sourceTitle,
                    artists = sourceArtists,
                    durationSeconds = sourceDurationSeconds,
                    quality = capsuleVideoQuality,
                ),
            isRelevant = {
                player.currentMediaItem?.mediaId == canonicalMediaId &&
                    videoPlaybackState.value.preferredMode == CapsulePlaybackMode.VIDEO &&
                    videoPlaybackState.value.phase == CapsuleVideoPhase.RESOLVING
            },
            onResult = { resolved ->
                resolved.onFailure { throwable ->

                    val noMatchingVideo =
                        isDefiniteNoVideoMatch(throwable)

                    Timber.tag("CapsuleVideo").w(
                        throwable,
                        if (noMatchingVideo) {
                            "No trustworthy YouTube Music video available for $canonicalMediaId"
                        } else {
                            "YouTube VIDEO request failed for $canonicalMediaId"
                        },
                    )

                    videoOriginalMediaItem = null
                    videoOriginalMediaId = null
                    videoPlaybackState.value =
                        CapsuleVideoPlaybackState(
                            preferredMode = CapsulePlaybackMode.AUDIO,
                            mode = CapsulePlaybackMode.AUDIO,
                            phase =
                                if (noMatchingVideo) {
                                    CapsuleVideoPhase.UNAVAILABLE
                                } else {
                                    CapsuleVideoPhase.REQUEST_ERROR
                                },
                            mediaId = canonicalMediaId,
                            videoId = null,
                            message =
                                throwable.message
                                    ?: if (noMatchingVideo) {
                                        "Official YouTube Music video unavailable"
                                    } else {
                                        "VIDEO temporarily unavailable — continuing with audio"
                                    },
                        )
                }.onSuccess { video ->
                    videoSuspendedForScreenOff = false

                    val position = player.currentPosition.coerceAtLeast(0L)
                    val wasPlaying = player.playWhenReady

                    val videoItem =
                        MediaItem.Builder()
                            .setMediaId(canonicalMediaId)
                            .setUri("$CAPSULE_VIDEO_SCHEME://play/${video.videoId}")
                            /*
                             * The itag belongs in the key. The resolver caches
                             * per quality, so without it a 360p and a 720p run
                             * of the same clip share one cache entry and get
                             * spans from two different files stitched together.
                             */
                            .setCustomCacheKey(
                                "$CAPSULE_VIDEO_CACHE_PREFIX" +
                                    "${video.videoId}:${video.format.itag}",
                            )
                            .setMediaMetadata(currentItem.mediaMetadata)
                            .apply {
                                currentItem.localConfiguration?.tag?.let(::setTag)
                            }
                            .build()

                    /*
                     * The stream has already been resolved and validated. Mark
                     * VIDEO as active before replaceMediaItem so the 16:9 stage
                     * appears immediately while Media3 buffers the first frame.
                     */
                    videoPlaybackState.value =
                        CapsuleVideoPlaybackState(
                            preferredMode = CapsulePlaybackMode.VIDEO,
                            mode = CapsulePlaybackMode.VIDEO,
                            phase = CapsuleVideoPhase.PLAYING,
                            mediaId = canonicalMediaId,
                            videoId = video.videoId,
                            qualityLabel = video.qualityLabel,
                            width = video.videoFormat.width,
                            height = video.videoFormat.height,
                            message = null,
                        )

                    player.replaceMediaItem(currentIndex, videoItem)
                    player.seekTo(currentIndex, position)
                    player.prepare()
                    player.playWhenReady = wasPlaying
                }
            },
        )
    }

    private fun leaveCapsuleVideoMode() {
        videoResolveCoordinator.cancel()

        val currentItem = player.currentMediaItem ?: run {
            videoPlaybackState.value =
                CapsuleVideoPlaybackState(
                    preferredMode = CapsulePlaybackMode.AUDIO,
                )
            return
        }

        if (!isCurrentCapsuleVideoItem()) {
            videoOriginalMediaItem = null
            videoOriginalMediaId = null
            videoPlaybackState.value =
                CapsuleVideoPlaybackState(
                    preferredMode = CapsulePlaybackMode.AUDIO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.IDLE,
                    mediaId = currentItem.mediaId,
                )
            return
        }

        restoreOriginalAudioItem(
            failureMessage = null,
            preferredModeAfter = CapsulePlaybackMode.AUDIO,
        )
    }

    private fun restoreAudioFromVideoFailure(message: String) {
        restoreOriginalAudioItem(
            failureMessage = message,
            preferredModeAfter = CapsulePlaybackMode.AUDIO,
            failurePhase = CapsuleVideoPhase.REQUEST_ERROR,
            invalidateFailedVideo = true,
        )
    }

    private fun restoreOriginalAudioItem(
        failureMessage: String?,
        preferredModeAfter: CapsulePlaybackMode,
        failurePhase: CapsuleVideoPhase = CapsuleVideoPhase.UNAVAILABLE,
        invalidateFailedVideo: Boolean = failureMessage != null,
    ) {
        videoResolveCoordinator.cancel()

        val currentItem = player.currentMediaItem ?: return
        val canonicalMediaId =
            videoOriginalMediaId
                ?.takeIf { it.isNotBlank() }
                ?: currentItem.mediaId
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return

        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val original =
            videoOriginalMediaItem
                ?.takeIf { it.mediaId == canonicalMediaId }
                ?: MediaItem.Builder()
                    .setMediaId(canonicalMediaId)
                    .setUri(canonicalMediaId)
                    .setCustomCacheKey(canonicalMediaId)
                    .setMediaMetadata(currentItem.mediaMetadata)
                    .apply {
                        currentItem.localConfiguration?.tag?.let(::setTag)
                    }
                    .build()

        val failedVideoId = videoPlaybackState.value.videoId
        if (invalidateFailedVideo && !failedVideoId.isNullOrBlank()) {
            YouTubeVideoResolver.invalidate(failedVideoId)
            scope.launch(Dispatchers.IO) {
                /*
                 * VIDEO bytes live in videoCache since the cache split, and the
                 * muxed key now carries an itag suffix, so this has to be a
                 * prefix scan over the right cache rather than one exact key.
                 */
                runCatching {
                    videoCache.keys
                        .filter { key ->
                            (
                                key.startsWith(CAPSULE_VIDEO_CACHE_PREFIX) &&
                                    key
                                        .removePrefix(CAPSULE_VIDEO_CACHE_PREFIX)
                                        .substringBefore(':') == failedVideoId
                            ) || (
                                key.startsWith(CAPSULE_VIDEO_STREAM_CACHE_PREFIX) &&
                                    key.contains(":$failedVideoId:")
                            )
                        }
                        .forEach(videoCache::removeResource)
                }
            }
        }

        videoOriginalMediaItem = null
        videoOriginalMediaId = null
        videoPlaybackState.value =
            CapsuleVideoPlaybackState(
                preferredMode = preferredModeAfter,
                mode = CapsulePlaybackMode.AUDIO,
                phase =
                    if (failureMessage == null) {
                        CapsuleVideoPhase.IDLE
                    } else {
                        failurePhase
                    },
                mediaId = canonicalMediaId,
                videoId = failedVideoId,
                message = failureMessage,
            )

        player.replaceMediaItem(currentIndex, original)
        player.seekTo(currentIndex, position)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun registerCapsuleScreenStateReceiver() {
        if (screenStateReceiverRegistered) return

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    screenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                registerReceiver(
                    screenStateReceiver,
                    filter,
                )
            }
            screenStateReceiverRegistered = true
        }.onFailure {
            Timber.tag("CapsuleVideo").w(it, "Could not register screen-state receiver")
        }
    }

    private fun unregisterCapsuleScreenStateReceiver() {
        if (!screenStateReceiverRegistered) return

        runCatching {
            unregisterReceiver(screenStateReceiver)
        }
        screenStateReceiverRegistered = false
    }

    private fun canUseCapsuleVideoForScreen(): Boolean {
        if (!screenInteractive) return false

        val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        return keyguardManager?.isKeyguardLocked != true
    }

    private fun isDefiniteNoVideoMatch(throwable: Throwable): Boolean {
        val message =
            generateSequence(throwable) { it.cause }
                .mapNotNull { it?.message }
                .joinToString(" ")
                .lowercase()

        return message.contains(
            "official music video is unavailable for this song",
        ) ||
            message.contains(
                "youtube music did not find a trustworthy official clip",
            ) ||
            message.contains(
                "did not find a matching official video",
            )
    }

    private fun suspendCapsuleVideoForScreenOff() {
        val state = videoPlaybackState.value
        val videoWasRequested =
            state.preferredMode == CapsulePlaybackMode.VIDEO ||
                state.mode == CapsulePlaybackMode.VIDEO ||
                state.phase == CapsuleVideoPhase.RESOLVING ||
                isCurrentCapsuleVideoItem()

        if (!videoWasRequested) return

        /*
         * Screen-off is a hard end of this track's VIDEO session.
         * Playback immediately returns to AUDIO and unlock never schedules a
         * new extractor request by itself.
         */
        videoSuspendedForScreenOff = false
        videoResolveCoordinator.cancel()

        if (isCurrentCapsuleVideoItem()) {
            restoreOriginalAudioItem(
                failureMessage = null,
                preferredModeAfter = CapsulePlaybackMode.AUDIO,
                invalidateFailedVideo = false,
            )
        } else {
            val currentId = player.currentMediaItem?.mediaId

            videoOriginalMediaItem = null
            videoOriginalMediaId = null
            videoPlaybackState.value =
                CapsuleVideoPlaybackState(
                    preferredMode = CapsulePlaybackMode.AUDIO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.IDLE,
                    mediaId = currentId,
                    videoId = null,
                    qualityLabel = null,
                    width = null,
                    height = null,
                    message = null,
                )
        }
    }

    private fun resumeCapsuleVideoAfterUnlockIfAllowed() {
        /*
         * Deliberately no automatic VIDEO resume. Unlocking stays in AUDIO.
         */
        videoSuspendedForScreenOff = false

        val state = videoPlaybackState.value
        if (
            state.preferredMode == CapsulePlaybackMode.VIDEO &&
            state.mode != CapsulePlaybackMode.VIDEO
        ) {
            videoPlaybackState.value =
                state.copy(
                    preferredMode = CapsulePlaybackMode.AUDIO,
                    mode = CapsulePlaybackMode.AUDIO,
                    phase = CapsuleVideoPhase.IDLE,
                    videoId = null,
                    qualityLabel = null,
                    width = null,
                    height = null,
                    message = null,
                )
        }
    }

    private fun isCurrentCapsuleVideoItem(): Boolean =
        player.currentMediaItem
            ?.localConfiguration
            ?.uri
            ?.scheme
            ?.equals(CAPSULE_VIDEO_SCHEME, ignoreCase = true) == true

    fun retryCurrentFromFreshStream() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        streamRetryJob?.cancel()
        streamRetryJob = null
        playbackRecoveryCoordinator.resetRetry(mediaId)
        playbackRecoveryCoordinator.cancelNetworkRecovery()
        CapsuleAudioEngine.clearTrackClientFailures(mediaId)
        CapsuleAudioEngine.invalidateCachedStreamUrls(mediaId)
        audioResolveCoordinator.cancelMedia(mediaId) {
            playbackUrlCache.remove(mediaId)
        }
        player.prepare()
        player.playWhenReady = true
    }

    private fun reloadAudioResolveConfig(primaryProfileId: String) {
        streamRetryJob?.cancel()
        streamRetryJob = null
        playbackRecoveryCoordinator.cancelNetworkRecovery(clearWaiting = false)
        audioResolveCoordinator.invalidatePrefetches()
        audioResolveCoordinator.invalidatePolicy(
            invalidatePrefetch = false,
            onInvalidate = playbackUrlCache::clear,
        )
        playbackRecoveryCoordinator.clearRetryBudget()
        // Explicit client changes affect future resolves only. The current
        // already-open stream must keep feeding AudioTrack without a reprepare.
        CapsuleAudioEngine.clearStreamClientFailures()
        Timber.tag(CAPSULE_RESOLVE_TAG).i(
            "Audio client priority updated first=%s; future resolves updated, current playback preserved",
            primaryProfileId,
        )
        prefetchUpcomingAudio()
    }

    private fun recreateAudioSources(
        index: Int = player.currentMediaItemIndex,
        position: Long = player.currentPosition.coerceAtLeast(0L),
        playWhenReady: Boolean = player.playWhenReady,
    ) {
        val queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        if (queue.isEmpty()) return
        val timeline = player.currentTimeline
        val shuffled = generateSequence(timeline.getFirstWindowIndex(true)) { window ->
            timeline.getNextWindowIndex(window, REPEAT_MODE_OFF, true)
        }.takeWhile { it != C.INDEX_UNSET }.take(queue.size).toList().toIntArray()
        player.setMediaItems(queue, index.coerceIn(queue.indices), position)
        if (shuffled.size == queue.size) {
            player.setShuffleOrder(DefaultShuffleOrder(shuffled, System.currentTimeMillis()))
        }
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private fun scheduleStreamRefreshRetry(
        mediaId: String,
        refreshCipherConfig: Boolean,
        retryReason: String,
        retryDelayMs: Long,
    ) {
        val retryPosition = player.currentPosition
        val retryIndex = player.currentMediaItemIndex
        val retryPlayWhenReady = player.playWhenReady
        val retryPositionGeneration = playbackPositionGeneration.snapshot()

        streamRetryJob?.cancel()
        streamRetryJob =
            scope.launch {
                if (refreshCipherConfig) {
                    val configChanged =
                        try {
                            withContext(Dispatchers.IO) {
                                CapsuleAudioEngine.refreshAfterStreamRejection()
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.tag("MusicService").w(
                                error,
                                "Player config refresh failed after $retryReason",
                            )
                            false
                        }

                    if (configChanged) {
                        CapsuleAudioEngine.clearStreamClientFailures()
                        Timber.tag("MusicService").i(
                            "Player config changed after stream rejection; restored stream clients",
                        )
                    }
                }

                delay(retryDelayMs)
                if (
                    player.currentMediaItem?.mediaId != mediaId ||
                    player.currentMediaItemIndex != retryIndex ||
                    !playbackPositionGeneration.isCurrent(retryPositionGeneration) ||
                    player.playWhenReady != retryPlayWhenReady ||
                    CapsuleAudioEngine.playbackBlockedExceptionOrNull() != null
                ) {
                    Timber.tag("MusicService").i(
                        "Skipping stale stream retry for $mediaId after $retryReason",
                    )
                    return@launch
                }

                player.seekTo(retryIndex, retryPosition)
                player.prepare()
                player.playWhenReady = retryPlayWhenReady
                Timber.tag("MusicService").i(
                    "Retrying playback for $mediaId after $retryReason",
                )
            }
    }

    private fun PlaybackException.httpStatusCodeOrNull(): Int? {
        var t: Throwable? = cause
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }

    private fun PlaybackException.isTransientNetworkFailure(): Boolean {
        val networkErrorCodes =
            setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )

        return generateSequence(this as Throwable?) { it?.cause }
            .take(8)
            .any { throwable ->
                (throwable is PlaybackException && throwable.errorCode in networkErrorCodes) ||
                    throwable is UnknownHostException ||
                    throwable is ConnectException ||
                    throwable is NoRouteToHostException ||
                    throwable is SocketTimeoutException ||
                    throwable is SocketException
            }
    }


    private fun createMediaSourceFactory(): MediaSource.Factory {
        val dataSourceFactory = createDataSourceFactory()
        val extractorsFactory =
            ExtractorsFactory {
                arrayOf(
                    Mp4Extractor(),
                    FragmentedMp4Extractor(),
                    MatroskaExtractor(),
                    Mp3Extractor(),
                    FlacExtractor(),
                )
            }

        val delegate =
            DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory,
            )
        val progressive =
            ProgressiveMediaSource.Factory(
                dataSourceFactory,
                extractorsFactory,
            )
        val loadErrorHandlingPolicy = CapsuleLoadErrorHandlingPolicy()
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        return object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val uri = mediaItem.localConfiguration?.uri
                val videoId =
                    uri
                        ?.takeIf { it.scheme.equals(CAPSULE_VIDEO_SCHEME, ignoreCase = true) }
                        ?.lastPathSegment
                        ?.takeIf { it.isNotBlank() }

                if (videoId == null) {
                    return delegate.createMediaSource(mediaItem)
                }

                val resolved = YouTubeVideoResolver.peekResolved(videoId)
                    ?: return delegate.createMediaSource(mediaItem)

                val videoChild =
                    mediaItem
                        .buildUpon()
                        .setCustomCacheKey(
                            "$CAPSULE_VIDEO_STREAM_CACHE_PREFIX" +
                                "video:$videoId:${resolved.videoFormat.itag}",
                        )
                        .build()
                val videoSource = progressive.createMediaSource(videoChild)

                val audioUrl = resolved.audioStreamUrl
                val audioFormat = resolved.audioFormat
                if (audioUrl.isNullOrBlank() || audioFormat == null) {
                    return videoSource
                }

                val audioChild =
                    MediaItem.Builder()
                        .setMediaId("${mediaItem.mediaId}:capsule-video-audio")
                        .setUri("$CAPSULE_VIDEO_SCHEME://audio/$videoId")
                        .setCustomCacheKey(
                            "$CAPSULE_VIDEO_STREAM_CACHE_PREFIX" +
                                "audio:$videoId:${audioFormat.itag}",
                        )
                        .build()
                val audioSource = progressive.createMediaSource(audioChild)

                return MergingMediaSource(
                    true,
                    true,
                    videoSource,
                    audioSource,
                )
            }

            override fun getSupportedTypes(): IntArray =
                delegate.getSupportedTypes()

            override fun setDrmSessionManagerProvider(
                drmSessionManagerProvider: DrmSessionManagerProvider,
            ): MediaSource.Factory {
                delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
                progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
            ): MediaSource.Factory {
                delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                return this
            }
        }
    }

    private fun updateAudioOffload(enabled: Boolean) {
        player.setOffloadEnabled(
            shouldEnableAudioOffload(
                requested = enabled,
                crossfadeDurationMs = crossfadeDurationMs.value,
            ),
        )
    }

    private fun createRenderersFactory() = CapsuleAudioRenderersFactory(this)

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (playbackStats.totalPlayTimeMs >= (
                dataStore[HistoryDuration]?.times(1000f)
                    ?: 30000f
            ) &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            // Analytics describes the item that finished, which can already be absent
            // from the current queue. Do not depend on its asynchronous metadata recovery.
            val historyMetadata = mediaItem.metadata
            val historyEvent = Event(
                songId = mediaItem.mediaId,
                timestamp = LocalDateTime.now(),
                playTime = playbackStats.totalPlayTimeMs,
            )
            database.query {
                try {
                    if (!recordPlayback(historyEvent, historyMetadata)) {
                        Timber.tag("MusicService").w(
                            "Playback history skipped: no metadata for finished item id=%s",
                            historyEvent.songId,
                        )
                    }
                } catch (error: SQLException) {
                    reportRecoverableException(
                        "MusicService", "insert playback-history event id=${historyEvent.songId}", error,
                    )
                }
            }

            scrobbleCoordinator.onPlaybackFinished(
                mediaId = mediaItem.mediaId,
                totalPlayTimeMs = playbackStats.totalPlayTimeMs,
            )

            ioScope.launch {
                try {
                    registerRemoteListeningHistory(mediaItem.mediaId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.tag("MusicService").v(error, "Remote listening-history sync failed")
                }
            }
        }
    }

    private suspend fun registerRemoteListeningHistory(mediaId: String) {
        if (!isRemoteHistorySyncAllowed()) return

        val attemptedUrls = LinkedHashSet<String>()

        suspend fun registerTrackingUrl(url: String): Boolean {
            attemptedUrls += url
            return YouTube.registerPlayback(playbackTracking = url)
                .onFailure {
                    reportException(it)
                }.isSuccess
        }

        val cachedPlaybackUrl = database.format(mediaId).first()?.playbackUrl
        val cachedSuccess = cachedPlaybackUrl?.let { registerTrackingUrl(it) } == true
        if (cachedSuccess) return

        val playbackTracking =
            CapsuleAudioEngine.playerResponseForMetadata(mediaId, null)
                .getOrNull()
                ?.playbackTracking
                ?: return

        for (
            trackingUrl in listOfNotNull(
                playbackTracking.videostatsPlaybackUrl?.baseUrl,
                playbackTracking.videostatsWatchtimeUrl?.baseUrl,
            )
        ) {
            if (trackingUrl.isBlank() || trackingUrl in attemptedUrls) continue
            registerTrackingUrl(trackingUrl)
        }
    }

    private suspend fun isRemoteHistorySyncAllowed(): Boolean {
        if (!dataStore.getAsync(YtmSyncKey, true)) return false
        val cookie = dataStore.getAsync(InnerTubeCookieKey, "")
        return cookie.isNotBlank() && cookie.contains("SAPISID")
    }

    private fun createTransientSongFromMedia(media: com.nikhil.yt.models.MediaMetadata): Song {
        val songEntity = SongEntity(
            id = media.id,
            title = media.title,
            duration = media.duration,
            thumbnailUrl = media.thumbnailUrl,
            albumId = media.album?.id,
            albumName = media.album?.title,
            explicit = media.explicit,
        )

        val artists = media.artists.map { artist ->
            ArtistEntity(
                id = artist.id ?: "LA_unknown_${artist.name}",
                name = artist.name,
                thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
            )
        }

        val album = media.album?.let { alb ->
            AlbumEntity(
                id = alb.id,
                playlistId = null,
                title = alb.title,
                year = null,
                thumbnailUrl = media.thumbnailUrl,
                themeColor = null,
                songCount = 1,
                duration = media.duration,
            )
        }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }

    private fun capturePersistentPlaybackSnapshot(): PersistentPlaybackSnapshot? {
        if (currentQueue == EmptyQueue || player.mediaItemCount <= 0) return null

        val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.metadata }
        if (mediaItemsSnapshot.isEmpty()) return null

        val currentMediaItemIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
        val automixAutoAddedSnapshot =
            synchronized(autoAddedMediaIds) { autoAddedMediaIds.toList() }
        val playerState = capturePersistentPlayerState() ?: return null

        return PersistentPlaybackSnapshot(
            queue =
                currentQueue.toPersistQueue(
                    title = queueTitle,
                    items = mediaItemsSnapshot,
                    mediaItemIndex = currentMediaItemIndex,
                    position = currentPosition,
                ),
            automix =
                PersistQueue(
                    title = "automix",
                    items = automixSnapshot,
                    mediaItemIndex = 0,
                    position = 0,
                    automixSeedMediaId =
                        automixRuntime.seedMediaId
                            ?.trim()
                            ?.takeIf { it.isNotBlank() },
                    automixAutoAddedMediaIds = automixAutoAddedSnapshot,
                ),
            playerState = playerState,
        )
    }

    private fun capturePersistentPlayerState(): PersistPlayerState? {
        if (player.mediaItemCount <= 0) return null
        return PersistPlayerState(
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            volume = playerVolume.value,
            currentPosition = player.currentPosition,
            currentMediaItemIndex = player.currentMediaItemIndex,
            playbackState = player.playbackState,
        )
    }

    private fun finishTaskRemovedPlaybackShutdown() {
        runCatching { stopAndClearPlayback() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        stopSelf()
    }


    private fun scheduleTogetherShutdown() {
        if (!togetherShutdownGate.tryBegin()) return

        try {
            App.instance.launchLifecycleCleanup {
                try {
                    stopTogetherInternal()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportRecoverableException(
                        "MusicService",
                        "complete Together shutdown",
                        error,
                    )
                }
            }
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "schedule Together shutdown", error)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackPersistence.cancelPending()
        unregisterCapsuleScreenStateReceiver()
        unregisterBluetoothReceiver()
        scheduleTogetherShutdown()
        discordPresenceOwner.stop()
        scrobbleCoordinator.destroy()
        try {
            connectivityObserver.unregister()
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "unregister connectivity observer", error)
        }
        playbackFocusController.abandonFocus()
        try {
            audioEffectsController.release()
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "release audio effects during destroy", error)
        }
        /*
         * onDestroy runs on the main thread. Never fsync the queue here.
         * Task-removal shutdown waits for its IO flush before stopSelf;
         * other destruction paths get a best-effort immutable snapshot.
         */
        try {
            if (dataStore.get(PersistentQueueKey, true)) {
                capturePersistentPlaybackSnapshot()?.let { snapshot ->
                    playbackPersistence.flush(snapshot)
                }
            }
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "capture final playback snapshot", error)
        }
        try {
            mediaSession.release()
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "release media session", error)
        }
        try {
            crossfadeAudio?.release()
            crossfadeAudio = null
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "release crossfade audio", error)
        }
        try {
            player.removeListener(this)
            player.removeListener(sleepTimer)
            player.release()
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "release player", error)
        }
        scopeJob.cancel()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        val result = super.onBind(intent) ?: binder
        if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        discordPresenceOwner.stop()

        val stopMusicOnTaskClearEnabled = dataStore.get(StopMusicOnTaskClearKey, false)

        try {
            val state = togetherSessionState.value
            val isHostSessionActive =
                state is com.nikhil.yt.together.TogetherSessionState.Hosting ||
                    state is com.nikhil.yt.together.TogetherSessionState.HostingOnline ||
                    (state is com.nikhil.yt.together.TogetherSessionState.Joined &&
                        state.role is com.nikhil.yt.together.TogetherRole.Host)

            val isPlaybackInactive = player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0

            if (shouldStopServiceOnTaskRemoved(stopMusicOnTaskClearEnabled, isHostSessionActive, isPlaybackInactive)) {
                if (isHostSessionActive && isPlaybackInactive) {
                    scheduleTogetherShutdown()
                    runCatching { togetherSessionState.value = com.nikhil.yt.together.TogetherSessionState.Idle }
                    stopSelf()
                    return
                }

                if (stopMusicOnTaskClearEnabled) {
                    playbackPersistence.cancelPending()

                    val shutdownSnapshot =
                        if (dataStore.get(PersistentQueueKey, true)) {
                            capturePersistentPlaybackSnapshot()
                        } else {
                            null
                        }

                    if (shutdownSnapshot == null) {
                        finishTaskRemovedPlaybackShutdown()
                        return
                    }

                    /*
                     * Keep the service alive just long enough for the atomic
                     * fsync/rename sequence. Main stays free, while stopSelf
                     * is deferred until persistence has finished.
                     */
                    playbackPersistence.flush(shutdownSnapshot) {
                        withContext(Dispatchers.Main) {
                            finishTaskRemovedPlaybackShutdown()
                        }
                    }
                    return
                }
            }
        } catch (error: Exception) {
            reportRecoverableException("MusicService", "handle task-removal shutdown", error)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureStartedAsForeground()

        when (intent?.action) {
            "com.nikhil.yt.ACTION_PREV" -> {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            "com.nikhil.yt.ACTION_NEXT" -> {
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            "com.nikhil.yt.ACTION_PLAY_PAUSE" -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            "com.nikhil.yt.ACTION_REWIND" -> {
                // Jumps back exactly 10,000 milliseconds (10 seconds)
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPos)
            }
            "com.nikhil.yt.ACTION_FORWARD" -> {
                player.seekTo(
                    forwardSeekPositionMs(
                        currentPositionMs = player.currentPosition,
                        durationMs = player.duration,
                    ),
                )
            }
        }

        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }


    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) ensureStartedAsForeground()
        runCatching { super.onUpdateNotification(session, startInForegroundRequired) }
            .onFailure { reportException(it) }
    }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        internal fun shouldStopServiceOnTaskRemoved(
            stopMusicOnTaskClearEnabled: Boolean,
            isHostSessionActive: Boolean,
            isPlaybackInactive: Boolean,
        ): Boolean = (isHostSessionActive && isPlaybackInactive) || stopMusicOnTaskClearEnabled

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001

        /* Single tag so a field run can be filtered down to the resolve path. */
        private const val CAPSULE_RESOLVE_TAG = "CapsuleResolve"

        private const val HEALTHY_PLAYBACK_RESET_MS = 5_000L

        /* Do not re-resolve a URL that still has this much life left. */
        private const val PREFETCH_FRESHNESS_MS = 60_000L

        /*
         * Floors for the two blocking resolves in the ResolvingDataSource.
         * They exist so a stalled network cannot pin the loader thread; with
         * prefetch in place they should almost never be reached.
         */
        private const val AUDIO_RESOLVE_TIMEOUT_MS = 20_000L
        private const val VIDEO_RESOLVE_TIMEOUT_MS = 20_000L
        const val MAX_CONSECUTIVE_TRACK_FAILURES = 3
    }
}
