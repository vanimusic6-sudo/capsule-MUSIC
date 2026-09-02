/*
 * Capsule MUSIC
 * Safety-first YouTube AUDIO stream resolver.
 *
 * Goals:
 * - a 403 for one song/client must never poison that client for every song;
 * - 429/bot-check must stop request escalation instead of cycling clients;
 * - normal AUDIO stream acquisition uses a credential-free InnerTube session;
 * - current fallback clients avoid identities with known mandatory PO-token
 *   requirements where possible;
 * - stream URL cache is scoped by video + client identity + itag.
 *
 * GPL-3.0
 */

package com.nikhil.yt.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.constants.PlayerStreamClient
import com.nikhil.yt.innertube.CapsuleAnonymousSession
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.VISIONOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_DOWNGRADED
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.pages.NewPipeUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.plugins.ResponseException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"

    /*
     * 403 usually means this exact stream/client combination is bad.
     * It must NOT globally disable the client.
     */
    private const val TRACK_CLIENT_BACKOFF_MS = 10 * 60 * 1000L

    /*
     * 429 and explicit bot-detection are different: continuing to rotate
     * clients creates more traffic. Stop network resolution for a while.
     */
    private const val GLOBAL_BREAKER_MS = 10 * 60 * 1000L

    /* Hard request budget for one logical AUDIO resolve. */
    private const val MAX_PLAYER_REQUESTS_PER_RESOLVE = 3
    private const val MAX_STREAM_PROBES_PER_CLIENT = 2
    private const val MAX_STREAM_PROBES_PER_RESOLVE = 4

    /* Pause before the single retry of a probe that never reached the network. */
    private const val TRANSPORT_RETRY_DELAY_MS = 350L
    private const val FRESH_CACHE_SAFETY_MS = 30_000L

    /* Bound slow-growing in-memory state on very long sessions. */
    private const val MAX_STREAM_CACHE_ENTRIES = 800
    private const val MAX_FAILED_CLIENT_ENTRIES = 1_200

    @Volatile
    private var streamClientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    @Volatile
    private var globalPlaybackBreakerUntilMs: Long = 0L

    @Volatile
    private var globalPlaybackBreakerReason: String? = null

    private fun currentStreamClient(): OkHttpClient {
        val current = YouTube.streamProxy

        streamClientPair?.let { (proxy, client) ->
            if (proxy == current) return client
        }

        val client =
            /*
             * OkHttp defaults callTimeout to zero, meaning no ceiling at all.
             * These probes decide whether playback starts, so an unbounded call
             * is simply a bug: a stalled connection had no way to fail.
             */
            OkHttpClient.Builder()
                .proxy(current)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .readTimeout(java.time.Duration.ofSeconds(8))
                .writeTimeout(java.time.Duration.ofSeconds(8))
                .callTimeout(java.time.Duration.ofSeconds(10))
                .build()

        streamClientPair = current to client
        return client
    }

    /*
     * Current safety policy:
     *
     * VISIONOS:
     *   primary anonymous client; current upstream policy does not declare a
     *   mandatory GVS/Player PO token.
     *
     * TVHTML5:
     *   conservative compatibility fallback. Used anonymously.
     *
     * TVHTML5_DOWNGRADED:
     *   compatibility fallback maintained upstream without a currently
     *   declared mandatory PO-token policy.
     *
     * Android/iOS/Android-VR client identities remain defined for source
     * compatibility but are intentionally NOT part of this automatic chain.
     */
    private val MAIN_CLIENT: YouTubeClient = VISIONOS


    private data class CachedStreamUrl(
        val url: String,
        val expiresAtMs: Long,
    )

    private data class StreamProbeResult(
        val success: Boolean,
        val statusCode: Int? = null,
        /*
         * True when the probe never reached YouTube at all: DNS failure, no
         * route, TLS or timeout. That says something about this device's
         * network, not about the stream or the client identity, so it must not
         * be treated as evidence against either.
         */
        val transportFailure: Boolean = false,
    )

    internal class ResolveRequestBudget(
        private val maxPlayerRequests: Int = MAX_PLAYER_REQUESTS_PER_RESOLVE,
        private val maxStreamProbes: Int = MAX_STREAM_PROBES_PER_RESOLVE,
    ) {
        var playerRequestsUsed: Int = 0
            private set
        var streamProbesUsed: Int = 0
            private set

        fun tryConsumePlayerRequest(): Boolean {
            if (playerRequestsUsed >= maxPlayerRequests) return false
            playerRequestsUsed += 1
            return true
        }

        fun tryConsumeStreamProbe(): Boolean {
            if (streamProbesUsed >= maxStreamProbes) return false
            streamProbesUsed += 1
            return true
        }

        val playerBudgetExhausted: Boolean
            get() = playerRequestsUsed >= maxPlayerRequests

        val streamProbeBudgetExhausted: Boolean
            get() = streamProbesUsed >= maxStreamProbes
    }

    private val streamUrlCache =
        ConcurrentHashMap<String, CachedStreamUrl>()

    /*
     * Key = videoId + exact client identity.
     *
     * This is the critical fix for the bug where one 403 disabled IOS /
     * ANDROID_VR / another client for every song until app restart.
     */
    private val failedTrackClientsUntil =
        ConcurrentHashMap<String, Long>()

    fun invalidateCachedStreamUrls(videoId: String) {
        /*
         * Stream URL invalidation must NOT clear the per-track client failure
         * map. Otherwise MusicService can mark a client as failed on 403 and
         * immediately erase that protection in the next line.
         */
        val prefix = "$videoId:"
        streamUrlCache.keys.removeIf { it.startsWith(prefix) }
    }

    fun clearTrackClientFailures(videoId: String) {
        val prefix = "$videoId:"
        failedTrackClientsUntil.keys.removeIf { it.startsWith(prefix) }
    }

    fun clearPlaybackSafetyState() {
        streamUrlCache.clear()
        failedTrackClientsUntil.clear()
        globalPlaybackBreakerUntilMs = 0L
        globalPlaybackBreakerReason = null
        CapsuleAnonymousSession.reset()
    }

    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
        val normalizedClientKey =
            normalizeStreamClientKey(clientKey)

        if (normalizedClientKey.isEmpty()) return

        when (httpStatusCode) {
            403 -> {
                failedTrackClientsUntil[
                    buildFailedClientKey(
                        videoId = videoId,
                        clientKey = normalizedClientKey,
                    )
                ] =
                    System.currentTimeMillis() +
                        TRACK_CLIENT_BACKOFF_MS
                pruneFailedClientMap()
            }

            429 -> {
                tripGlobalBreaker(
                    reason = "YouTube returned HTTP 429",
                )
            }
        }
    }

    fun markPreferredClientFailed(
        videoId: String,
        client: PlayerStreamClient,
        httpStatusCode: Int?,
    ) {
        /*
         * Legacy PlayerStreamClient values are kept only for source/settings
         * compatibility. They must not silently re-enable old Android-VR/iOS
         * playback identities.
         */
        val effective =
            if (client == PlayerStreamClient.TVHTML5) {
                TVHTML5
            } else {
                VISIONOS
            }

        markStreamClientFailed(
            videoId = videoId,
            clientKey = effectiveIdentityKey(effective),
            httpStatusCode = httpStatusCode,
        )
    }

    fun markBotDetectionFailure(reason: String? = null) {
        tripGlobalBreaker(
            reason =
                reason
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "YouTube bot-check: ${it.take(160)}" }
                    ?: "YouTube requested a bot check",
        )
    }

    private fun isStreamClientTemporarilyBlocked(
        videoId: String,
        client: YouTubeClient,
    ): Boolean {
        if (isGlobalBreakerActive()) return true

        val now = System.currentTimeMillis()
        val keys =
            listOf(
                buildFailedClientKey(
                    videoId = videoId,
                    clientKey = effectiveIdentityKey(client),
                ),
                buildFailedClientKey(
                    videoId = videoId,
                    clientKey = client.clientName,
                ),
            ).distinct()

        var blocked = false

        for (key in keys) {
            val until = failedTrackClientsUntil[key] ?: continue

            if (until <= now) {
                failedTrackClientsUntil.remove(key)
            } else {
                blocked = true
            }
        }

        return blocked
    }

    private fun normalizeStreamClientKey(
        clientKey: String?,
    ): String =
        clientKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()

    private fun effectiveIdentityKey(
        client: YouTubeClient,
    ): String =
        normalizeStreamClientKey(
            "${client.clientName}@${client.clientVersion}",
        )

    private fun buildFailedClientKey(
        videoId: String,
        clientKey: String,
    ): String =
        "$videoId:${normalizeStreamClientKey(clientKey)}"

    private fun tripGlobalBreaker(reason: String) {
        val now = System.currentTimeMillis()
        val newUntil = now + GLOBAL_BREAKER_MS

        if (newUntil > globalPlaybackBreakerUntilMs) {
            globalPlaybackBreakerUntilMs = newUntil
        }

        globalPlaybackBreakerReason = reason

        Timber.tag(logTag).w(
            "Global YouTube playback breaker opened for %d ms: %s",
            GLOBAL_BREAKER_MS,
            reason,
        )
    }

    private fun isGlobalBreakerActive(): Boolean {
        val until = globalPlaybackBreakerUntilMs

        if (until <= 0L) return false

        if (until <= System.currentTimeMillis()) {
            globalPlaybackBreakerUntilMs = 0L
            globalPlaybackBreakerReason = null
            return false
        }

        return true
    }

    private fun throwIfGlobalBreakerActive() {
        if (!isGlobalBreakerActive()) return

        val remainingSeconds =
            ((globalPlaybackBreakerUntilMs -
                System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(1L)

        throw PlaybackException(
            buildString {
                append("YouTube playback is cooling down")
                globalPlaybackBreakerReason?.let {
                    append(": ")
                    append(it)
                }
                append(" (")
                append(remainingSeconds)
                append("s)")
            },
            null,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient =
            PlayerStreamClient.ANDROID_VR,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.AUTO_SAFE,
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): Result<PlaybackData> =
        runCatching {
            throwIfGlobalBreakerActive()

            val budget = ResolveRequestBudget()
            val signatureTimestamp = getSignatureTimestampOrNull(videoId)
            val attempts =
                when (audioQuality) {
                    AudioQuality.HIGHEST ->
                        listOf(AudioQuality.HIGHEST, AudioQuality.HIGH)
                    AudioQuality.AUTO ->
                        listOf(AudioQuality.AUTO, AudioQuality.HIGH)
                    else -> listOf(audioQuality)
                }.distinct()

            var lastError: Throwable? = null

            try {
                for (attempt in attempts) {
                    if (budget.playerBudgetExhausted) break

                    val attemptResult =
                        runCatching {
                            playerResponseForPlaybackOnce(
                                videoId = videoId,
                                playlistId = playlistId,
                                audioQuality = attempt,
                                connectivityManager = connectivityManager,
                                preferredStreamClient = preferredStreamClient,
                                streamPolicy = streamPolicy,
                                networkMetered = networkMetered,
                                avoidCodecs = avoidCodecs,
                                signatureTimestamp = signatureTimestamp,
                                budget = budget,
                            )
                        }

                    if (attemptResult.isSuccess) {
                        return@runCatching attemptResult.getOrThrow()
                    }

                    lastError = attemptResult.exceptionOrNull()
                    if (isGlobalBreakerActive()) {
                        throw lastError
                            ?: IllegalStateException("YouTube playback breaker active")
                    }
                }

                throw lastError
                    ?: PlaybackException(
                        "YouTube AUDIO request budget exhausted",
                        null,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR,
                    )
            } finally {
                Timber.tag(logTag).i(
                    "AUDIO resolve stats videoId=%s player=%d/%d probes=%d/%d",
                    videoId,
                    budget.playerRequestsUsed,
                    MAX_PLAYER_REQUESTS_PER_RESOLVE,
                    budget.streamProbesUsed,
                    MAX_STREAM_PROBES_PER_RESOLVE,
                )
            }
        }

    private suspend fun playerResponseForPlaybackOnce(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        streamPolicy: AudioStreamPolicy,
        networkMetered: Boolean?,
        avoidCodecs: Set<String>,
        signatureTimestamp: Int?,
        budget: ResolveRequestBudget,
    ): PlaybackData {
        throwIfGlobalBreakerActive()

        Timber.tag(logTag).i(
            "Fetching safe AUDIO player response for videoId: %s, playlistId: %s",
            videoId,
            playlistId,
        )

        val preferredYouTubeClient =
            preferredClient(
                streamPolicy = streamPolicy,
                legacyPreference = preferredStreamClient,
            )

        val policyOrder =
            when (streamPolicy) {
                /*
                 * Ordered by what the field runs actually showed:
                 *
                 *   VISIONOS   179/179 first try. Stays first.
                 *   IOS        same family, same shape (anonymous, no
                 *              signatureTimestamp, uncipherd URLs) and fully
                 *              upstream-synced, so it is the likeliest of the
                 *              defined identities to work when VISIONOS does.
                 *   IOS_MUSIC  same family again, but its version string is
                 *              pinned by hand rather than synced, so it sits
                 *              behind IOS.
                 *   TV         0/92 right now. Kept so it revives on its own if
                 *              upstream fixes it, but never reached while the
                 *              iOS identities answer, and the per-resolve
                 *              request budget caps the chain anyway.
                 */
                AudioStreamPolicy.AUTO_SAFE,
                AudioStreamPolicy.VISIONOS,
                -> listOf(
                    VISIONOS,
                    YouTubeClient.IOS,
                    YouTubeClient.IOS_MUSIC,
                    YouTubeClient.TVHTML5_DOWNGRADED,
                    TVHTML5,
                )

                AudioStreamPolicy.IOS ->
                    listOf(
                        YouTubeClient.IOS,
                        VISIONOS,
                        YouTubeClient.IOS_MUSIC,
                    )

                AudioStreamPolicy.IOS_MUSIC ->
                    listOf(
                        YouTubeClient.IOS_MUSIC,
                        VISIONOS,
                        YouTubeClient.IOS,
                    )

                AudioStreamPolicy.TV_DOWNGRADED ->
                    listOf(
                        YouTubeClient.TVHTML5_DOWNGRADED,
                        VISIONOS,
                        YouTubeClient.IOS,
                        TVHTML5,
                    )

                AudioStreamPolicy.TVHTML5 ->
                    listOf(
                        TVHTML5,
                        VISIONOS,
                        YouTubeClient.IOS,
                        YouTubeClient.TVHTML5_DOWNGRADED,
                    )
            }

        val streamClients =
            buildList {
                add(preferredYouTubeClient)
                addAll(policyOrder)
            }
                .distinct()
                .filterNot { client ->
                    val blocked =
                        isStreamClientTemporarilyBlocked(
                            videoId = videoId,
                            client = client,
                        )

                    if (blocked) {
                        Timber.tag(logTag).w(
                            "Temporarily blocked only for %s: %s",
                            videoId,
                            effectiveIdentityKey(client),
                        )
                    }

                    blocked
                }

        if (streamClients.isEmpty()) {
            throwIfGlobalBreakerActive()
            throw PlaybackException(
                "No safe YouTube stream client is currently available for this track",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        var lastPlayerResponse: PlayerResponse? = null
        var lastFailure: Throwable? = null

        for ((index, client) in streamClients.withIndex()) {
            throwIfGlobalBreakerActive()
            if (!budget.tryConsumePlayerRequest()) break

            Timber.tag(logTag).i(
                "Trying safe AUDIO client %d/%d: %s",
                index + 1,
                streamClients.size,
                effectiveIdentityKey(client),
            )

            val playerResponseResult =
                CapsuleAnonymousSession.player(
                    videoId = videoId,
                    client = client,
                    signatureTimestamp = signatureTimestamp,
                )

            val playerFailure = playerResponseResult.exceptionOrNull()
            if (playerFailure != null) {
                lastFailure = playerFailure
                val failureKind = classifyThrowableFailure(playerFailure)

                Timber.tag(logTag).w(
                    playerFailure,
                    "Player request failed for %s (%s)",
                    effectiveIdentityKey(client),
                    failureKind,
                )

                when (failureKind) {
                    YouTubeFailureKind.RATE_LIMITED -> {
                        tripGlobalBreaker("YouTube returned HTTP 429")
                        throw PlaybackException(
                            "YouTube rate limited playback",
                            playerFailure,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.BOT_CHECK -> {
                        tripGlobalBreaker("YouTube requested a bot check")
                        throw PlaybackException(
                            "YouTube requested a bot check",
                            playerFailure,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.FORBIDDEN -> {
                        markStreamClientFailed(
                            videoId = videoId,
                            clientKey = effectiveIdentityKey(client),
                            httpStatusCode = 403,
                        )
                    }

                    YouTubeFailureKind.PERMANENT -> {
                        /*
                         * A removed/private/region-permanent item is a property
                         * of the content itself. Rotating clients only adds
                         * traffic and cannot make the item exist again.
                         */
                        throw PlaybackException(
                            "This track is unavailable",
                            playerFailure,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.LOGIN_REQUIRED,
                    YouTubeFailureKind.AGE_RESTRICTED,
                    -> {
                        /*
                         * These responses can be client-specific. Keep the hard
                         * request budget, but allow the next reviewed client.
                         * Never open the global breaker for login/age.
                         */
                    }

                    else -> Unit
                }

                continue
            }

            val playerResponse = playerResponseResult.getOrThrow()
            lastPlayerResponse = playerResponse

            val status = playerResponse.playabilityStatus.status
            if (status != "OK") {
                val reason = playerResponse.playabilityStatus.reason.orEmpty()
                val failureKind =
                    YouTubeFailureClassifier.classify(
                        playabilityStatus = status,
                        text = reason,
                    )

                when (failureKind) {
                    YouTubeFailureKind.RATE_LIMITED -> {
                        tripGlobalBreaker("YouTube returned a rate-limit response")
                        throw PlaybackException(
                            reason.ifBlank { "YouTube rate limited playback" },
                            null,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.BOT_CHECK -> {
                        tripGlobalBreaker(
                            "YouTube bot-check: ${reason.take(160)}",
                        )
                        throw PlaybackException(
                            reason.ifBlank { "YouTube requested a bot check" },
                            null,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.PERMANENT -> {
                        throw PlaybackException(
                            reason.ifBlank { "This track is unavailable" },
                            null,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    YouTubeFailureKind.AGE_RESTRICTED,
                    YouTubeFailureKind.LOGIN_REQUIRED,
                    -> {
                        /*
                         * Do not open the global breaker. A different reviewed
                         * client can legitimately return a playable response.
                         * The request budget still caps the total attempts.
                         */
                        lastFailure =
                            PlaybackException(
                                reason.ifBlank {
                                    "This track requires a different playback session"
                                },
                                null,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                            )
                    }

                    YouTubeFailureKind.FORBIDDEN -> {
                        markStreamClientFailed(
                            videoId = videoId,
                            clientKey = effectiveIdentityKey(client),
                            httpStatusCode = 403,
                        )
                    }

                    else -> Unit
                }

                Timber.tag(logTag).w(
                    "Client %s returned %s (%s): %s",
                    effectiveIdentityKey(client),
                    status,
                    failureKind,
                    reason,
                )
                continue
            }

            val isMetered =
                networkMetered ?: connectivityManager.isActiveNetworkMetered

            val candidates =
                selectAudioFormatCandidates(
                    playerResponse = playerResponse,
                    audioQuality = audioQuality,
                    networkMetered = isMetered,
                    avoidCodecs = avoidCodecs,
                )

            if (candidates.isEmpty()) continue

            val expectedDurationMs =
                playerResponse.videoDetails
                    ?.lengthSeconds
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.times(1000L)

            var probesForClient = 0

            candidateLoop@ for (candidate in candidates) {
                if (
                    expectedDurationMs != null &&
                    isLikelyPreview(candidate, expectedDurationMs)
                ) {
                    continue
                }

                val cacheKey =
                    buildCacheKey(
                        videoId = videoId,
                        itag = candidate.itag,
                        client = client,
                    )

                val now = System.currentTimeMillis()
                val cached = streamUrlCache[cacheKey]
                if (cached != null) {
                    if (cached.expiresAtMs > now + FRESH_CACHE_SAFETY_MS) {
                        val expiresInSeconds =
                            ((cached.expiresAtMs - now) / 1000L)
                                .coerceAtLeast(1L)
                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt()

                        Timber.tag(logTag).i(
                            "Safe AUDIO fresh-cache hit with %s, itag=%d (no probe)",
                            effectiveIdentityKey(client),
                            candidate.itag,
                        )

                        return PlaybackData(
                            audioConfig = playerResponse.playerConfig?.audioConfig,
                            videoDetails = playerResponse.videoDetails,
                            playbackTracking = playerResponse.playbackTracking,
                            format = candidate,
                            streamUrl = cached.url,
                            streamExpiresInSeconds = expiresInSeconds,
                        )
                    }

                    streamUrlCache.remove(cacheKey, cached)
                }

                if (probesForClient >= MAX_STREAM_PROBES_PER_CLIENT) break
                if (!budget.tryConsumeStreamProbe()) break
                probesForClient += 1

                val candidateUrl =
                    findUrlOrNull(
                        format = candidate,
                        videoId = videoId,
                        client = client,
                    ) ?: continue

                var probe = validateStatus(candidateUrl, client)

                /*
                 * One retry for a transport failure. Flaky VPN DNS very often
                 * resolves the same host on a second attempt a moment later.
                 */
                if (probe.transportFailure) {
                    Thread.sleep(TRANSPORT_RETRY_DELAY_MS)
                    probe = validateStatus(candidateUrl, client)
                }

                /*
                 * Still unreachable. The probe is an optimisation that catches
                 * a rejected URL early, not a precondition for playback, and
                 * here it has told us nothing about the stream. Fall through to
                 * the accept path below and let the player, which has its own
                 * retry policy, decide. Discarding a good response and burning
                 * the rest of the client chain cannot fix broken DNS.
                 */
                if (probe.transportFailure) {
                    Timber.tag(logTag).w(
                        "Accepting %s itag=%d unprobed: network unreachable",
                        effectiveIdentityKey(client),
                        candidate.itag,
                    )
                }

                if (!probe.success && !probe.transportFailure) {
                    when (probe.statusCode) {
                        403, 401 -> {
                            markStreamClientFailed(
                                videoId = videoId,
                                clientKey = effectiveIdentityKey(client),
                                httpStatusCode = 403,
                            )
                            /*
                             * A rejected first stream is enough evidence to
                             * leave this exact client for this track. Do not
                             * hammer five more formats from the same response.
                             */
                            break@candidateLoop
                        }

                        429 -> {
                            markStreamClientFailed(
                                videoId = videoId,
                                clientKey = effectiveIdentityKey(client),
                                httpStatusCode = 429,
                            )
                            throwIfGlobalBreakerActive()
                        }

                        else -> {
                            if (budget.streamProbeBudgetExhausted) break@candidateLoop
                            continue@candidateLoop
                        }
                    }
                }

                val expiresInSeconds =
                    playerResponse.streamingData?.expiresInSeconds ?: 300

                streamUrlCache[cacheKey] =
                    CachedStreamUrl(
                        url = candidateUrl,
                        expiresAtMs =
                            System.currentTimeMillis() +
                                expiresInSeconds * 1000L,
                    )
                pruneStreamUrlCache()

                Timber.tag(logTag).i(
                    "Safe AUDIO stream validated with %s, itag=%d",
                    effectiveIdentityKey(client),
                    candidate.itag,
                )

                return PlaybackData(
                    audioConfig = playerResponse.playerConfig?.audioConfig,
                    videoDetails = playerResponse.videoDetails,
                    playbackTracking = playerResponse.playbackTracking,
                    format = candidate,
                    streamUrl = candidateUrl,
                    streamExpiresInSeconds = expiresInSeconds,
                )
            }
        }

        throwIfGlobalBreakerActive()

        val lastStatus = lastPlayerResponse?.playabilityStatus?.status
        throw PlaybackException(
            buildString {
                append("No playable safe YouTube AUDIO stream")
                lastStatus?.let {
                    append(" (last status: ")
                    append(it)
                    append(")")
                }
                lastFailure?.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(": ")
                        append(it.take(160))
                    }
            },
            lastFailure,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }

    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        CapsuleAnonymousSession.player(
            videoId = videoId,
            client = MAIN_CLIENT,
            signatureTimestamp =
                getSignatureTimestampOrNull(videoId),
        )

    private fun preferredClient(
        streamPolicy: AudioStreamPolicy,
        legacyPreference: PlayerStreamClient,
    ): YouTubeClient =
        when (streamPolicy) {
            AudioStreamPolicy.AUTO_SAFE ->
                if (legacyPreference == PlayerStreamClient.TVHTML5) {
                    TVHTML5
                } else {
                    VISIONOS
                }

            AudioStreamPolicy.VISIONOS -> VISIONOS
            AudioStreamPolicy.IOS -> YouTubeClient.IOS
            AudioStreamPolicy.IOS_MUSIC -> YouTubeClient.IOS_MUSIC
            AudioStreamPolicy.TV_DOWNGRADED ->
                YouTubeClient.TVHTML5_DOWNGRADED
            AudioStreamPolicy.TVHTML5 -> TVHTML5
        }

    private fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        avoidCodecs: Set<String> = emptySet(),
    ): List<PlayerResponse.StreamingData.Format> =
        selectAudioFormatCandidatesForTest(
            formats = playerResponse.streamingData?.adaptiveFormats.orEmpty(),
            audioQuality = audioQuality,
            networkMetered = networkMetered,
            avoidCodecs = avoidCodecs,
        )

    internal fun selectAudioFormatCandidatesForTest(
        formats: List<PlayerResponse.StreamingData.Format>,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        avoidCodecs: Set<String> = emptySet(),
    ): List<PlayerResponse.StreamingData.Format> {
        val audioFormats =
            formats
                .asSequence()
                .filter { it.isAudio && it.bitrate > 0 }
                .filter {
                    it.url != null ||
                        it.signatureCipher != null ||
                        it.cipher != null
                }
                .filter { format ->
                    val codec = extractCodec(format.mimeType)?.lowercase()
                    codec == null || codec !in avoidCodecs
                }
                .toList()

        if (audioFormats.isEmpty()) return emptyList()

        val effectiveQuality =
            when (audioQuality) {
                AudioQuality.AUTO ->
                    if (networkMetered) AudioQuality.HIGH else AudioQuality.HIGHEST
                else -> audioQuality
            }

        val targetBitrateBps =
            when (effectiveQuality) {
                AudioQuality.LOW -> 70_000
                AudioQuality.HIGH -> 160_000
                AudioQuality.HIGHEST -> 320_000
                AudioQuality.AUTO -> null
            }

        val preferHigher =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenByDescending { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        val preferLowerAboveTarget =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenBy { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        return if (targetBitrateBps == null) {
            audioFormats.sortedWith(preferHigher)
        } else {
            val belowOrEqual = audioFormats.filter { it.bitrate <= targetBitrateBps }
            if (belowOrEqual.isNotEmpty()) {
                belowOrEqual.sortedWith(preferHigher)
            } else {
                val aboveOrEqual = audioFormats.filter { it.bitrate >= targetBitrateBps }
                if (aboveOrEqual.isNotEmpty()) {
                    aboveOrEqual.sortedWith(preferLowerAboveTarget)
                } else {
                    audioFormats.sortedWith(preferHigher)
                }
            }
        }
    }

    private fun extractCodec(
        mimeType: String,
    ): String? {
        val match =
            Regex("""codecs="([^"]+)"""")
                .find(mimeType)
                ?: return null

        return match
            .groupValues
            .getOrNull(1)
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
    }

    private fun codecRank(
        codec: String?,
    ): Int =
        when {
            codec.isNullOrBlank() -> 0
            codec.contains(
                "opus",
                ignoreCase = true,
            ) -> 3

            codec.contains(
                "mp4a",
                ignoreCase = true,
            ) -> 2

            else -> 1
        }

    private fun isLikelyPreview(
        format: PlayerResponse.StreamingData.Format,
        expectedDurationMs: Long,
    ): Boolean {
        val approx =
            format.approxDurationMs
                ?.toLongOrNull()
                ?: return false

        if (expectedDurationMs < 90_000L) {
            return false
        }

        return approx in
            1L..minOf(
                90_000L,
                (expectedDurationMs * 9L) / 10L,
            )
    }

    /*
     * One tiny range probe only.
     *
     * The old code issued up to three different range probes for some clients.
     * For safety and lower request volume, a single byte is enough to reject a
     * dead 403/429 URL before handing it to Media3.
     */
    private fun validateStatus(
        url: String,
        client: YouTubeClient,
    ): StreamProbeResult {
        return try {
            val httpUrl = url.toHttpUrlOrNull()
            val clientParam =
                httpUrl
                    ?.queryParameter("c")
                    ?.trim()
                    .orEmpty()

            val originReferer =
                StreamClientUtils
                    .resolveOriginReferer(clientParam)

            val request =
                okhttp3.Request
                    .Builder()
                    .get()
                    .header(
                        "User-Agent",
                        client.userAgent,
                    )
                    .header(
                        "Range",
                        "bytes=0-0",
                    )
                    .apply {
                        originReferer.origin?.let {
                            header("Origin", it)
                        }
                        originReferer.referer?.let {
                            header("Referer", it)
                        }
                    }
                    .url(url)
                    .build()

            val code =
                currentStreamClient()
                    .newCall(request)
                    .execute()
                    .use { it.code }

            StreamProbeResult(
                success =
                    code in 200..399 ||
                        code == 416,
                statusCode = code,
            )
        } catch (error: Exception) {
            val transport = isTransportFailure(error)

            Timber.tag(logTag).w(
                "Stream probe %s: %s",
                if (transport) "could not reach the network" else "failed",
                error.javaClass.simpleName,
            )

            if (!transport) reportException(error)

            StreamProbeResult(
                success = false,
                statusCode = null,
                transportFailure = transport,
            )
        }
    }

    /*
     * Local connectivity problems, not YouTube verdicts. VPN DNS in particular
     * fails to resolve individual googlevideo edge hosts fairly often, and that
     * used to be indistinguishable from a dead stream.
     */
    private fun isTransportFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { cause ->
                cause is java.net.UnknownHostException ||
                    cause is java.net.ConnectException ||
                    cause is java.net.NoRouteToHostException ||
                    cause is java.net.SocketTimeoutException ||
                    cause is java.io.InterruptedIOException ||
                    cause is javax.net.ssl.SSLException
            }

    private fun getSignatureTimestampOrNull(
        videoId: String,
    ): Int? =
        NewPipeUtils
            .getSignatureTimestamp(videoId)
            .onFailure {
                Timber.tag(logTag).w(
                    it,
                    "Failed to get signature timestamp",
                )
                reportException(it)
            }
            .getOrNull()

    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
    ): String? {
        var url =
            NewPipeUtils
                .getStreamUrl(
                    format,
                    videoId,
                    client,
                )
                .onFailure {
                    Timber.tag(logTag).w(
                        it,
                        "Failed to get stream URL",
                    )
                    reportException(it)
                }
                .getOrNull()
                ?: return null

        /*
         * The legacy URL helper can read global account PO-token state.
         * Playback here is anonymous, so strip only a token that exactly
         * matches Capsule's authenticated account state.
         */
        url =
            CapsuleAnonymousSession
                .stripAccountPoToken(
                    url = url,
                    client = client,
                )

        url =
            StreamClientUtils.patchClientVersion(
                url = url,
                clientVersion =
                    client.clientVersion,
            )

        return url
    }

    private fun buildCacheKey(
        videoId: String,
        itag: Int,
        client: YouTubeClient,
    ): String =
        "$videoId:" +
            "${effectiveIdentityKey(client)}:" +
            "$itag"

    private fun classifyThrowableFailure(
        throwable: Throwable,
    ): YouTubeFailureKind {
        val chain =
            generateSequence(throwable as Throwable?) { it?.cause }
                .take(8)
                .toList()

        val httpStatusCode =
            chain
                .filterIsInstance<ResponseException>()
                .firstOrNull()
                ?.response
                ?.status
                ?.value

        val text = chain.mapNotNull { it?.message }.joinToString(" ")

        return YouTubeFailureClassifier.classify(
            httpStatusCode = httpStatusCode,
            playabilityStatus = null,
            text = text,
        )
    }

    internal fun classifyPlayabilityForTest(
        status: String?,
        reason: String?,
    ): YouTubeFailureKind =
        YouTubeFailureClassifier.classify(
            playabilityStatus = status,
            text = reason,
        )

    internal fun shouldTryNextClientForTest(
        kind: YouTubeFailureKind,
    ): Boolean =
        when (kind) {
            YouTubeFailureKind.RATE_LIMITED,
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureKind.PERMANENT,
            -> false

            else -> true
        }

    private fun isBotDetectionError(
        reason: String,
    ): Boolean =
        YouTubeFailureClassifier.classify(text = reason) ==
            YouTubeFailureKind.BOT_CHECK

    fun isBotDetectionException(
        error: PlaybackException,
    ): Boolean {
        val text =
            buildString {
                append(error.message.orEmpty())
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 8) {
                    append(' ')
                    append(cause.message.orEmpty())
                    cause = cause.cause
                    depth += 1
                }
            }

        return isBotDetectionError(text)
    }

    private fun pruneStreamUrlCache() {
        val now = System.currentTimeMillis()
        streamUrlCache.entries.removeIf { it.value.expiresAtMs <= now }

        if (streamUrlCache.size <= MAX_STREAM_CACHE_ENTRIES) return

        streamUrlCache.entries
            .sortedBy { it.value.expiresAtMs }
            .take(streamUrlCache.size - MAX_STREAM_CACHE_ENTRIES)
            .forEach { streamUrlCache.remove(it.key, it.value) }
    }

    private fun pruneFailedClientMap() {
        val now = System.currentTimeMillis()
        failedTrackClientsUntil.entries.removeIf { it.value <= now }

        if (failedTrackClientsUntil.size <= MAX_FAILED_CLIENT_ENTRIES) return

        failedTrackClientsUntil.entries
            .sortedBy { it.value }
            .take(failedTrackClientsUntil.size - MAX_FAILED_CLIENT_ENTRIES)
            .forEach { failedTrackClientsUntil.remove(it.key, it.value) }
    }

}
