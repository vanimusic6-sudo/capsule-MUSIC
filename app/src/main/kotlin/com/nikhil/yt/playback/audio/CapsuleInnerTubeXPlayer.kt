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
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.models.YouTubeLocale as InnerTubeXLocale
import com.nikhil.yt.App
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.playback.audio.potoken.PoTokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.nikhil.yt.innertube.PlaybackAuthState
import com.nikhil.yt.innertube.models.YouTubeLocale
import com.nikhil.yt.utils.GlobalLog
import java.net.Proxy
import kotlin.time.Clock

/**
 * Capsule's sole modern stream-extraction entry point.
 *
 * Important policy:
 * - visionOS is the default; explicit WEB choices use their own profiles;
 * - EJS is best-effort: library Faraday/parser fallbacks remain available after QuickJS failures;
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

    private val bundleMutex = Mutex()
    private val resolveMutex = Mutex()
    private val scheduler = AudioResolveScheduler()

    fun prioritizePlayback(mediaId: String) = scheduler.promote(mediaId)
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamClientFailures = ConcurrentHashMap<String, FailedStreamClients>()

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    private val networkGeneration = AtomicLong()

    private val poTokenGenerator: PoTokenGenerator by lazy {
        PoTokenGenerator(App.instance.applicationContext)
    }

    private fun tokenProvider(auth: PlaybackAuthState) =
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
                val configuredPlayer = auth.poTokenPlayer?.trim().orEmpty()
                val configuredGvs = auth.poTokenGvs?.trim().orEmpty()

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

    suspend fun prewarm(prewarmWebPoToken: Boolean = false) {
        // Capture/create the bundle under the same lock as playback so a
        // preference collector cannot close the transport during extraction.
        // Start extractor warmup first; the optional visitor-bound BotGuard
        // warmup then runs concurrently outside resolveMutex.
        val (preparation, webPoTokenVisitorData) = resolveMutex.withLock {
            if (CapsulePlaybackSafety.blockedExceptionOrNull() != null) return
            val extractionBundle = bundle()
            val auth = extractionBundle.key.auth
            val hasConfiguredPoTokens =
                auth.poTokenPlayer?.trim().orEmpty().isNotBlank() &&
                    auth.poTokenGvs?.trim().orEmpty().isNotBlank()
            val visitorData =
                auth.visitorData
                    ?.trim()
                    ?.takeIf {
                        prewarmWebPoToken &&
                            !hasConfiguredPoTokens &&
                            it.isNotBlank() &&
                            it != "null"
                    }
            extractionBundle.prewarm.start() to visitorData
        }

        // This prepares only the reusable WebView/BotGuard session. It does not
        // issue a YouTube /player request, rotate clients, or bypass the global
        // anti-bot/rate-limit breaker. Playback shares the same PoToken mutex,
        // so an early first track joins this work instead of creating another session.
        webPoTokenVisitorData?.let { visitorData ->
            poTokenGenerator.prewarm(visitorData)
        }

        try {
            preparation.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Replacing a bundle cancels its warmup, not the application's
            // visitor-data collector that happened to be waiting for it.
            Timber.tag(TAG).d("Startup prewarm superseded by a new extraction session")
        }
    }

    suspend fun refreshAfterStreamRejection(): Boolean =
        resolveMutex.withLock {
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            bundle().cipherService.refreshAfterStreamRejection()
        }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        streamPolicy: AudioStreamPolicy,
        clientOrder: List<String> = emptyList(),
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
    ): Result<PlaybackData> =
        try {
            val primaryProfileId = clientOrder.firstOrNull() ?: streamPolicy.playbackClientOverrideId
            val baseHints =
                ContentHints(
                    isUploaded = playlistId == "MLPT" || playlistId?.contains("MLPT") == true,
                    wantVideo = false,
                    playbackClientOverrideId = null,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    /* Capsule Media3 does not yet consume InnerTubeX chunk scheduling. */
                    allowBoundedRange = false,
                )

            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val stream =
                scheduler.run(videoId, priority) {
                    resolveMutex.withLock {
                        withTimeout(ENGINE_RESOLVE_TIMEOUT_MS) {
                            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
                            val extractionBundle = bundle()
                            Timber.tag(TAG).i(
                                "Resolving audio id=%s priority=%s primaryProfile=%s",
                                videoId,
                                priority,
                                primaryProfileId,
                            )
                            resolveWithSafeClientFallbacks(
                                extractionBundle = extractionBundle,
                                videoId = videoId,
                                baseHints = baseHints,
                                audioQuality = resolvedQuality,
                                primaryProfileId = primaryProfileId,
                                preferredProfiles = clientOrder,
                                priority = priority,
                            )
                        }
                    }
                }

            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            Result.success(stream.toPlaybackData())
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).w(
                timeout,
                "engine resolve timeout id=%s priority=%s budgetMs=%d",
                videoId,
                priority,
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
                CapsulePlaybackSafety.blockedExceptionOrNull() ?: if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(CapsulePlaybackSafety.blockedExceptionOrNull() ?: error)
        }

    /**
     * Metrolist-style client resilience with a Capsule-sized request budget.
     * Background work never rotates clients. Foreground playback uses a short vetted chain;
     * after a bot-check exactly one different family may be tried before escalation.
     */
    private suspend fun resolveWithSafeClientFallbacks(
        extractionBundle: ExtractionBundle,
        videoId: String,
        baseHints: ContentHints,
        audioQuality: InnerTubeXAudioQuality,
        primaryProfileId: String,
        preferredProfiles: List<String>,
        priority: AudioResolvePriority,
    ): ExtractedStream {
        val quarantinedAtStart = CapsulePlaybackSafety.quarantinedProfileIds()
        val perSongExcluded = failedStreamClients(videoId)
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = primaryProfileId,
                priority = priority,
                authenticated = extractionBundle.innerTube.hasSapCookieAuth(),
                isUploaded = baseHints.isUploaded == true,
                excludedProfiles = quarantinedAtStart + perSongExcluded,
                preferredProfiles = preferredProfiles,
            )

        if (plan.isEmpty()) {
            throw IllegalStateException(
                if (priority == AudioResolvePriority.PLAYBACK) {
                    "No safe AUDIO client profile is currently available"
                } else {
                    "AUDIO background resolve suppressed while its primary profile is quarantined"
                },
            )
        }

        var lastFailure: Exception? = null
        var botSignalAlreadySeen = quarantinedAtStart.isNotEmpty()
        var onlyPostBotProfile: String? = null

        for (profileId in plan) {
            if (onlyPostBotProfile != null && profileId != onlyPostBotProfile) continue
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }

            val wireGeneration = CapsulePlaybackSafety.wireBotSignalGeneration()
            Timber.tag(TAG).i(
                "AUDIO profile attempt id=%s priority=%s profile=%s",
                videoId,
                priority,
                profileId,
            )

            try {
                val stream =
                    extractDirectStream(
                        extractionBundle = extractionBundle,
                        videoId = videoId,
                        hints = baseHints.copy(playbackClientOverrideId = profileId),
                        audioQuality = audioQuality,
                    )
                if (profileId != primaryProfileId) {
                    Timber.tag(TAG).i(
                        "AUDIO fallback recovered id=%s primary=%s selected=%s",
                        videoId,
                        primaryProfileId,
                        profileId,
                    )
                }
                return stream
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val kind =
                    CapsulePlaybackSafety.classifyFailureSince(
                        error = failure,
                        wireGenerationBeforeAttempt = wireGeneration,
                    )

                if (kind == YouTubeFailureKind.RATE_LIMITED) {
                    CapsulePlaybackSafety.observeFailure(failure)
                    throw failure
                }

                if (kind == YouTubeFailureKind.BOT_CHECK) {
                    CapsulePlaybackSafety.markProfileBotCheck(profileId)
                    markStreamClientFailed(videoId, profileId)
                    Timber.tag(TAG).w(
                        "AUDIO bot-check isolated id=%s priority=%s profile=%s",
                        videoId,
                        priority,
                        profileId,
                    )

                    if (priority != AudioResolvePriority.PLAYBACK) throw failure

                    if (botSignalAlreadySeen) {
                        CapsulePlaybackSafety.markBotDetectionFailure(
                            "confirmed across multiple AUDIO client profiles",
                        )
                        throw failure
                    }

                    val crossFamily =
                        CapsuleAudioFallbackPolicy.crossFamilyFallback(plan, profileId)
                            ?: throw failure
                    botSignalAlreadySeen = true
                    onlyPostBotProfile = crossFamily
                    lastFailure = failure
                    delay(CapsuleAudioFallbackPolicy.fallbackDelayMs(kind))
                    continue
                }

                val canFallback = CapsuleAudioFallbackPolicy.canFallbackAfter(kind)
                if (canFallback) {
                    markStreamClientFailed(videoId, profileId)
                }

                // After one bot-check, the single cross-family recovery gets one chance only.
                if (onlyPostBotProfile != null || !canFallback) throw failure

                val fallbackDelayMs = CapsuleAudioFallbackPolicy.fallbackDelayMs(kind)
                Timber.tag(TAG).w(
                    "AUDIO client-local failure id=%s profile=%s kind=%s; fallbackDelayMs=%d",
                    videoId,
                    profileId,
                    kind,
                    fallbackDelayMs,
                )
                lastFailure = failure
                if (fallbackDelayMs > 0L) delay(fallbackDelayMs)
            }
        }

        throw lastFailure ?: IllegalStateException("No safe AUDIO client profile produced a stream")
    }

    private suspend fun extractDirectStream(
        extractionBundle: ExtractionBundle,
        videoId: String,
        hints: ContentHints,
        audioQuality: InnerTubeXAudioQuality,
    ): ExtractedStream {
        var sabrRollovers = 0
        var excludedClients = failedStreamClients(videoId)

        while (true) {
            val extracted =
                requireNotNull(
                    extractionBundle.extractor.extract(
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
        networkGeneration.incrementAndGet()
    }

    fun onNetworkChanged() {
        streamClientFailures.clear()
        networkGeneration.incrementAndGet()
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
        val key = sessionSnapshot()
        currentBundle?.takeIf { it.key == key }?.let { return it }

        return bundleMutex.withLock {
            val lockedKey = sessionSnapshot()
            currentBundle?.takeIf { it.key == lockedKey }?.let { return@withLock it }

            currentBundle?.closeSafely()

            val httpClient = createHttpClient(lockedKey.proxy)
            val innerTube =
                InnerTube(httpClient = httpClient, logger = logger).also { playbackInnerTube ->
                    playbackInnerTube.locale =
                        InnerTubeXLocale(
                            gl = lockedKey.locale.gl,
                            hl = lockedKey.locale.hl,
                        )
                    playbackInnerTube.replaceSession(
                        cookie = lockedKey.auth.cookie,
                        visitorData = lockedKey.auth.visitorData,
                        dataSyncId = lockedKey.auth.dataSyncId,
                        authUser = "0",
                        useLoginForBrowse = lockedKey.useLoginForBrowse,
                    )
                }
            val remoteStore = RemotePlayerConfigStore(httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(httpClient, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        DiagnosticYtConfigParser(
                            YtConfigParserImpl(httpClient, innerTube, remoteStore, logger)
                                .withEmbeddedConfigFallback(),
                        ),
                    cipherService = cipherService,
                    innerTube = innerTube,
                    fallbackStrategy = CapsuleAudioClientStrategy,
                    tokenProvider = tokenProvider(lockedKey.auth),
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

    // Compare the complete immutable context; never log credentials or use lossy hashCode keys.
    private data class SessionSnapshot(
        val generation: Long,
        val proxy: Proxy?,
        val locale: YouTubeLocale,
        val auth: PlaybackAuthState,
        val useLoginForBrowse: Boolean,
    ) {
        override fun toString(): String = "generation=$generation"
    }

    private fun sessionSnapshot() = SessionSnapshot(
        networkGeneration.get(), YouTube.proxy, YouTube.locale,
        YouTube.authState, YouTube.useLoginForBrowse,
    )

    fun sessionIdentity(): Any = sessionSnapshot()

    private fun createHttpClient(selectedProxy: Proxy?): HttpClient =
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
            engine {
                config {
                    retryOnConnectionFailure(false)
                    addInterceptor(CapsuleAudioRequestInterceptor())
                }
                selectedProxy?.let { proxy = it }
            }
        }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(App.instance.applicationContext)
    }

    private val logger =
        InnerTubeLogger { event ->
            if (!GlobalLog.isEnabled) return@InnerTubeLogger
            val details =
                event.details.entries.joinToString(prefix = " [", postfix = "]") {
                    "${it.key}=${it.value}"
                }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    /**
     * Match Metrolist's recovery path: a failed regular watch-page config is
     * retried through the anonymous embedded config instead of poisoning WEB
     * playback. The embedded request intentionally never carries login cookies.
     */
    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(
                        videoId,
                        useLoginCookies = false,
                    )
                }
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
            videoDetails = mediaMetadata?.let { metadata ->
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = metadata.title,
                    author = metadata.author,
                    channelId = metadata.channelId,
                    lengthSeconds = metadata.durationSeconds?.toString(),
                    musicVideoType = metadata.musicVideoType,
                    viewCount = metadata.viewCount,
                    thumbnail = com.nikhil.yt.innertube.models.Thumbnails(metadata.thumbnails.map {
                        com.nikhil.yt.innertube.models.Thumbnail(it.url, it.width, it.height)
                    }),
                )
            },
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
        val key: SessionSnapshot,
        val httpClient: HttpClient,
        val innerTube: InnerTube,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    ) {
        val prewarm = SharedPrewarm(prewarmScope) {
            val startedAt = System.nanoTime()
            try {
                Timber.tag(TAG).i("Shared extractor prewarm started session=%s", key)
                extractor.prewarm()
                Timber.tag(TAG).i(
                    "Shared extractor prewarm completed session=%s elapsedMs=%d",
                    key,
                    (System.nanoTime() - startedAt) / 1_000_000L,
                )
            } catch (cancelled: TimeoutCancellationException) {
                Timber.tag(TAG).w("Shared extractor prewarm timed out; resolve will continue on demand")
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.tag(TAG).w(failure, "Shared extractor prewarm failed; resolve will continue on demand")
                throw failure
            }
        }

        suspend fun closeSafely() {
            prewarm.cancelAndJoin()
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
                "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"
        }
    }
}
