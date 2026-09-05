/*
 * Capsule MUSIC
 * Modern AUDIO extraction backend built on MetrolistGroup/InnerTubeX.
 *
 * The legacy InnerTube module remains responsible for browse/search/account
 * features. Playback is isolated here so YouTube player/cipher churn cannot
 * destabilize the rest of the application.
 *
 * GPL-3.0
 */
package com.nikhil.yt.playback.audio

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.models.YouTubeLocale as InnerTubeXLocale
import com.nikhil.yt.App
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.playback.audio.potoken.PoTokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Capsule's sole modern stream-extraction entry point.
 *
 * Important policy:
 * - every saved policy currently normalizes to the direct visionOS profile;
 * - cipher-dependent WEB profiles stay disabled after rapid switching proved
 *   capable of leaving the shared EJS solver in a repeated failure state;
 * - only one extraction runs at a time, so swipe bursts cannot create a bank
 *   of simultaneous player requests;
 * - parser/source failures are remembered per song for five minutes, so a bad
 *   client can roll over without poisoning playback globally or causing a
 *   rapid client carousel.
 */
object CapsuleInnerTubeXPlayer {
    private const val TAG = "CapsuleInnerTubeX"
    private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    /* One network request gets 8 s; the complete InnerTubeX client chain gets 18 s. */
    private const val PER_REQUEST_TIMEOUT_MS = 8_000L
    private const val ENGINE_RESOLVE_TIMEOUT_MS = 18_000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60
    private const val MAX_SABR_ROLLOVERS = 1
    private const val MAX_CIPHER_FAILURE_DETAIL_LENGTH = 180

    private val bundleMutex = Mutex()
    private val resolveMutex = Mutex()
    private val streamClientFailures = ConcurrentHashMap<String, FailedStreamClients>()

    /**
     * InnerTubeX deliberately converts QuickJsException into an empty cipher
     * result, so it never reaches the Result failure returned to this class.
     * Observe that library event and retire cipher profiles for the rest of
     * this process session. Network changes must not clear a deterministic
     * player-JS failure.
     */
    @Volatile
    private var cipherSessionFailure: String? = null

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    @Volatile
    private var networkGeneration: Long = 0L

    private val poTokenGenerator: PoTokenGenerator by lazy {
        PoTokenGenerator(App.instance.applicationContext)
    }

    private val tokenProvider =
        object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? {
                val configuredPlayer = YouTube.poTokenPlayer?.trim().orEmpty()
                val configuredGvs = YouTube.poTokenGvs?.trim().orEmpty()

                if (configuredPlayer.isNotBlank() && configuredGvs.isNotBlank()) {
                    return PoTokenResult(
                        playerRequestToken = configuredPlayer,
                        streamingDataToken = configuredGvs,
                        visitorData = visitorData,
                    )
                }

                return poTokenGenerator
                    .getWebClientPoToken(
                        videoId = videoId,
                        visitorData = visitorData,
                    )
                    ?.let { generated ->
                        PoTokenResult(
                            playerRequestToken = generated.playerRequestPoToken,
                            streamingDataToken = generated.streamingDataPoToken,
                            visitorData = visitorData,
                        )
                    }
            }

            override suspend fun close() {
                poTokenGenerator.close()
            }
        }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String,
        val streamHeaders: Map<String, String>,
    )

    suspend fun prewarm() = bundle().extractor.prewarm()

    suspend fun refreshAfterStreamRejection(): Boolean =
        bundle().cipherService.refreshAfterStreamRejection()

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        streamPolicy: AudioStreamPolicy,
    ): Result<PlaybackData> =
        try {
            val playbackClientOverrideId = profileOverride(streamPolicy)
            cipherSessionFailure
                ?.takeIf { playbackClientOverrideId != "VISIONOS" }
                ?.let { failure ->
                    throw IllegalStateException(
                        "Cipher playback is disabled for this session after EJS failure: $failure",
                    )
                }

            val hints =
                ContentHints(
                    isUploaded = playlistId == "MLPT" || playlistId?.contains("MLPT") == true,
                    wantVideo = false,
                    playbackClientOverrideId = playbackClientOverrideId,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    /*
                     * Capsule's existing Media3 source does not yet implement
                     * InnerTubeX's explicit chunk scheduler. Do not advertise a
                     * transport feature the consumer cannot honour.
                     */
                    allowBoundedRange = false,
                )

            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val stream =
                withTimeout(ENGINE_RESOLVE_TIMEOUT_MS) {
                    resolveMutex.withLock {
                        extractDirectStream(
                            videoId = videoId,
                            hints = hints,
                            audioQuality = resolvedQuality,
                        )
                    }
                }

            Result.success(stream.toPlaybackData())
        } catch (timeout: TimeoutCancellationException) {
            Timber.tag(TAG).w(
                timeout,
                "engine resolve timeout id=%s budgetMs=%d",
                videoId,
                ENGINE_RESOLVE_TIMEOUT_MS,
            )
            Result.failure(
                SocketTimeoutException(
                    "InnerTubeX audio resolve exceeded ${ENGINE_RESOLVE_TIMEOUT_MS} ms",
                ).apply { initCause(timeout) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            Result.failure(
                if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(error)
        }

    private suspend fun extractDirectStream(
        videoId: String,
        hints: ContentHints,
        audioQuality: InnerTubeXAudioQuality,
    ): ExtractedStream {
        var sabrRollovers = 0
        var excludedClients = failedStreamClients(videoId)

        while (true) {
            val extracted =
                requireNotNull(
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients,
                        audioQuality = audioQuality,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "InnerTubeX returned no playable AUDIO stream" }

            if (extracted.sabrBootstrap == null) {
                return extracted
            }

            /*
             * Capsule currently consumes direct GVS URLs only. A SABR-only
             * result is a client-local incompatibility, not a reason to crash
             * the resolver. Retire that client for this song and permit exactly
             * one bounded rollover.
             */
            val sabrClient =
                extracted.clientName
                    .substringBefore('@')
                    .trim()
                    .takeIf { it.isNotBlank() }

            if (
                sabrClient == null ||
                sabrRollovers >= MAX_SABR_ROLLOVERS ||
                sabrClient in excludedClients
            ) {
                throw IllegalStateException(
                    "InnerTubeX returned SABR-only audio and no safe direct rollover remains",
                )
            }

            markStreamClientFailed(videoId, sabrClient)
            excludedClients = failedStreamClients(videoId)
            sabrRollovers += 1
            Timber.tag(TAG).w(
                "Rejected SABR-only stream id=%s client=%s rollover=%d/%d",
                videoId,
                sabrClient,
                sabrRollovers,
                MAX_SABR_ROLLOVERS,
            )
        }
    }

    fun markStreamClientFailed(
        videoId: String,
        clientName: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalized = clientName?.substringBefore('@')?.trim()?.takeIf { it.isNotBlank() } ?: return
        streamClientFailures.compute(videoId) { _, failures ->
            FailedStreamClients(
                clientNames = failures?.clientNames.orEmpty() + normalized,
                failedAtMs = nowMs,
            )
        }
        Timber.tag(TAG).w("Per-song client rollover id=%s failedClient=%s", videoId, normalized)
    }

    fun clearTrackClientFailures(videoId: String) {
        streamClientFailures.remove(videoId)
    }

    fun clearStreamClientFailures() {
        streamClientFailures.clear()
    }

    fun clearPlaybackState() {
        streamClientFailures.clear()
        networkGeneration += 1L
    }

    fun onNetworkChanged() {
        streamClientFailures.clear()
        networkGeneration += 1L
    }

    private fun failedStreamClients(
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val failures = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failures.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failures)
            return emptySet()
        }
        return failures.clientNames
    }

    private suspend fun bundle(): ExtractionBundle {
        val key = bundleKey()
        currentBundle?.takeIf { it.key == key }?.let { return it }

        return bundleMutex.withLock {
            val lockedKey = bundleKey()
            currentBundle?.takeIf { it.key == lockedKey }?.let { return@withLock it }

            currentBundle?.closeSafely()

            val httpClient = createHttpClient()
            val innerTube =
                InnerTube(httpClient = httpClient, logger = logger).also { playbackInnerTube ->
                    playbackInnerTube.locale =
                        InnerTubeXLocale(
                            gl = YouTube.locale.gl,
                            hl = YouTube.locale.hl,
                        )
                    playbackInnerTube.replaceSession(
                        cookie = YouTube.cookie,
                        visitorData = YouTube.visitorData,
                        dataSyncId = YouTube.dataSyncId,
                        authUser = "0",
                        useLoginForBrowse = YouTube.useLoginForBrowse,
                    )
                }
            val remoteStore = RemotePlayerConfigStore(httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(httpClient, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser = YtConfigParserImpl(httpClient, innerTube, remoteStore, logger),
                    cipherService = cipherService,
                    innerTube = innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )

            ExtractionBundle(
                key = lockedKey,
                httpClient = httpClient,
                innerTube = innerTube,
                cipherService = cipherService,
                extractor = extractor,
            ).also { currentBundle = it }
        }
    }

    /**
     * Only non-sensitive hashes are used as the identity key. If account,
     * visitor, locale, proxy or token configuration changes, the extraction
     * transport is rebuilt instead of reusing stale session state.
     */
    private fun bundleKey(): String =
        listOf(
            networkGeneration,
            YouTube.proxy?.toString().orEmpty(),
            YouTube.locale.gl,
            YouTube.locale.hl,
            YouTube.cookie?.hashCode() ?: 0,
            YouTube.visitorData?.hashCode() ?: 0,
            YouTube.dataSyncId?.hashCode() ?: 0,
            YouTube.useLoginForBrowse,
            !YouTube.poTokenGvs.isNullOrBlank(),
            !YouTube.poTokenPlayer.isNullOrBlank(),
        ).hashCode().toString()

    private fun createHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = PER_REQUEST_TIMEOUT_MS
                connectTimeoutMillis = PER_REQUEST_TIMEOUT_MS
                socketTimeoutMillis = PER_REQUEST_TIMEOUT_MS
            }
            YouTube.proxy?.let { configuredProxy ->
                engine {
                    proxy = configuredProxy
                }
            }
        }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(App.instance.applicationContext)
    }

    private val logger =
        InnerTubeLogger { event ->
            val details =
                event.details.entries.joinToString(prefix = " [", postfix = "]") {
                    "${it.key}=${it.value}"
                }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            if (
                event.tag == "EjsChallengeSolver" &&
                "EJS solve failed" in message &&
                "QuickJsException" in message
            ) {
                if (cipherSessionFailure == null) {
                    cipherSessionFailure = message.take(MAX_CIPHER_FAILURE_DETAIL_LENGTH)
                    Timber.tag(TAG).e(
                        "Cipher session breaker opened after deterministic QuickJS failure",
                    )
                }
            }
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    private fun profileOverride(policy: AudioStreamPolicy): String {
        check(policy.normalizedForPlayback() == AudioStreamPolicy.VISIONOS)
        return "VISIONOS"
    }

    private fun AudioQuality.toInnerTubeX(
        connectivityManager: ConnectivityManager,
    ): InnerTubeXAudioQuality =
        when (this) {
            AudioQuality.HIGHEST,
            AudioQuality.HIGH,
            -> InnerTubeXAudioQuality.HIGH
            AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) {
                    InnerTubeXAudioQuality.LOW
                } else {
                    InnerTubeXAudioQuality.AUTO
                }
        }

    private fun ExtractedStream.toPlaybackData(): PlaybackData {
        val fullMimeType =
            if (codecs.isNullOrBlank()) {
                mimeType.orEmpty()
            } else {
                "${mimeType.orEmpty()}; codecs=\"$codecs\""
            }

        return PlaybackData(
            audioConfig =
                if (loudnessDb != null || perceptualLoudnessDb != null) {
                    PlayerResponse.PlayerConfig.AudioConfig(loudnessDb, perceptualLoudnessDb)
                } else {
                    null
                },
            videoDetails = null,
            playbackTracking =
                playbackTracking?.let {
                    PlayerResponse.PlaybackTracking(
                        videostatsPlaybackUrl =
                            it.playbackUrl?.let(PlayerResponse.PlaybackTracking::VideostatsPlaybackUrl),
                        videostatsWatchtimeUrl =
                            it.watchtimeUrl?.let(PlayerResponse.PlaybackTracking::VideostatsWatchtimeUrl),
                    )
                },
            format =
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = audioUrl,
                    mimeType = fullMimeType,
                    bitrate = bitrate ?: 0,
                    contentLength = contentLengthBytes,
                    quality = "",
                    averageBitrate = bitrate,
                    approxDurationMs = mediaMetadata?.durationSeconds?.times(1000L)?.toString(),
                    audioSampleRate = sampleRate,
                    loudnessDb = loudnessDb,
                    perceptualLoudnessDb = perceptualLoudnessDb,
                ),
            streamUrl = audioUrl,
            streamExpiresInSeconds =
                expiresAt
                    ?.let {
                        ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L)
                            .toInt()
                    }
                    ?.coerceAtLeast(1)
                    ?: DEFAULT_STREAM_TTL_SECONDS,
            streamClient = clientName,
            streamHeaders = headers,
        )
    }

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long,
    )

    private data class ExtractionBundle(
        val key: String,
        val httpClient: HttpClient,
        val innerTube: InnerTube,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    ) {
        suspend fun closeSafely() {
            try {
                cipherService.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).d(error, "Cipher service disposal failed")
            }
            runCatching { innerTube.close() }
                .onFailure { Timber.tag(TAG).d(it, "InnerTube close failed") }
            runCatching { httpClient.close() }
                .onFailure { Timber.tag(TAG).d(it, "HTTP client close failed") }
        }
    }

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences =
            context.getSharedPreferences("capsule_innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL

        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit().putString("json", value).apply()

        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit().putLong("cached_at_ms", value).apply()

        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit().putString("source_url", value).apply()

        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit().putString("etag", value).apply()

        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }
}
