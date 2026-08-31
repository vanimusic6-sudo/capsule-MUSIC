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
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.VISIONOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_DOWNGRADED
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.pages.NewPipeUtils
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
            OkHttpClient.Builder()
                .proxy(current)
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

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> =
        arrayOf(
            VISIONOS,
            TVHTML5_DOWNGRADED,
            TVHTML5,
        )

    private data class CachedStreamUrl(
        val url: String,
        val expiresAtMs: Long,
    )

    private data class StreamProbeResult(
        val success: Boolean,
        val statusCode: Int? = null,
    )

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

            val attempts =
                when (audioQuality) {
                    AudioQuality.HIGHEST ->
                        listOf(
                            AudioQuality.HIGHEST,
                            AudioQuality.HIGH,
                        )

                    AudioQuality.AUTO ->
                        listOf(
                            AudioQuality.AUTO,
                            AudioQuality.HIGH,
                        )

                    else -> listOf(audioQuality)
                }.distinct()

            var lastError: Throwable? = null

            for (attempt in attempts) {
                val attemptResult =
                    runCatching {
                        playerResponseForPlaybackOnce(
                            videoId = videoId,
                            playlistId = playlistId,
                            audioQuality = attempt,
                            connectivityManager =
                                connectivityManager,
                            preferredStreamClient =
                                preferredStreamClient,
                            streamPolicy = streamPolicy,
                            networkMetered = networkMetered,
                            avoidCodecs = avoidCodecs,
                        )
                    }

                if (attemptResult.isSuccess) {
                    return@runCatching attemptResult.getOrThrow()
                }

                lastError = attemptResult.exceptionOrNull()

                /*
                 * Never turn a global breaker into a second quality attempt.
                 */
                if (isGlobalBreakerActive()) {
                    throw lastError
                        ?: IllegalStateException(
                            "YouTube playback breaker active",
                        )
                }
            }

            throw lastError
                ?: IllegalStateException(
                    "Failed to resolve stream",
                )
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
    ): PlaybackData {
        throwIfGlobalBreakerActive()

        Timber.tag(logTag).i(
            "Fetching safe AUDIO player response for videoId: %s, playlistId: %s",
            videoId,
            playlistId,
        )

        val signatureTimestamp =
            getSignatureTimestampOrNull(videoId)

        val preferredYouTubeClient =
            preferredClient(
                streamPolicy = streamPolicy,
                legacyPreference = preferredStreamClient,
            )

        val policyOrder =
            when (streamPolicy) {
                AudioStreamPolicy.AUTO_SAFE,
                AudioStreamPolicy.VISIONOS,
                -> listOf(
                    VISIONOS,
                    YouTubeClient.TVHTML5_DOWNGRADED,
                    TVHTML5,
                )

                AudioStreamPolicy.TV_DOWNGRADED ->
                    listOf(
                        YouTubeClient.TVHTML5_DOWNGRADED,
                        VISIONOS,
                        TVHTML5,
                    )

                AudioStreamPolicy.TVHTML5 ->
                    listOf(
                        TVHTML5,
                        VISIONOS,
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

            val playerResponse =
                playerResponseResult
                    .onFailure {
                        lastFailure = it
                        Timber.tag(logTag).w(
                            it,
                            "Player request failed for %s",
                            effectiveIdentityKey(client),
                        )
                    }
                    .getOrNull()
                    ?: continue

            lastPlayerResponse = playerResponse

            if (playerResponse.playabilityStatus.status != "OK") {
                val reason =
                    playerResponse
                        .playabilityStatus
                        .reason
                        .orEmpty()

                if (isBotDetectionError(reason)) {
                    tripGlobalBreaker(
                        reason =
                            "YouTube bot-check: " +
                                reason.take(160),
                    )

                    throw PlaybackException(
                        reason.ifBlank {
                            "YouTube requested a bot check"
                        },
                        null,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR,
                    )
                }

                /*
                 * UNPLAYABLE/embedding-disabled/etc. is a legitimate
                 * per-client result. Try the next allowed safe client without
                 * globally punishing it.
                 */
                Timber.tag(logTag).w(
                    "Client %s returned %s: %s",
                    effectiveIdentityKey(client),
                    playerResponse.playabilityStatus.status,
                    reason,
                )
                continue
            }

            val isMetered =
                networkMetered
                    ?: connectivityManager
                        .isActiveNetworkMetered

            val candidates =
                selectAudioFormatCandidates(
                    playerResponse = playerResponse,
                    audioQuality = audioQuality,
                    networkMetered = isMetered,
                    avoidCodecs = avoidCodecs,
                )

            if (candidates.isEmpty()) {
                continue
            }

            val expectedDurationMs =
                playerResponse
                    .videoDetails
                    ?.lengthSeconds
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.times(1000L)

            for (candidate in candidates.asSequence().take(6)) {
                if (
                    expectedDurationMs != null &&
                    isLikelyPreview(
                        format = candidate,
                        expectedDurationMs =
                            expectedDurationMs,
                    )
                ) {
                    continue
                }

                val cacheKey =
                    buildCacheKey(
                        videoId = videoId,
                        itag = candidate.itag,
                        client = client,
                    )

                val cached = streamUrlCache[cacheKey]

                val candidateUrl =
                    if (
                        cached != null &&
                        cached.expiresAtMs >
                            System.currentTimeMillis()
                    ) {
                        cached.url
                    } else {
                        findUrlOrNull(
                            format = candidate,
                            videoId = videoId,
                            client = client,
                        )
                    } ?: continue

                val probe =
                    validateStatus(
                        url = candidateUrl,
                        client = client,
                    )

                if (!probe.success) {
                    when (probe.statusCode) {
                        403 -> {
                            markStreamClientFailed(
                                videoId = videoId,
                                clientKey =
                                    effectiveIdentityKey(client),
                                httpStatusCode = 403,
                            )
                        }

                        429 -> {
                            markStreamClientFailed(
                                videoId = videoId,
                                clientKey =
                                    effectiveIdentityKey(client),
                                httpStatusCode = 429,
                            )
                            throwIfGlobalBreakerActive()
                        }
                    }

                    continue
                }

                val expiresInSeconds =
                    playerResponse
                        .streamingData
                        ?.expiresInSeconds
                        ?: 300

                streamUrlCache[cacheKey] =
                    CachedStreamUrl(
                        url = candidateUrl,
                        expiresAtMs =
                            System.currentTimeMillis() +
                                expiresInSeconds * 1000L,
                    )

                Timber.tag(logTag).i(
                    "Safe AUDIO stream validated with %s, itag=%d",
                    effectiveIdentityKey(client),
                    candidate.itag,
                )

                return PlaybackData(
                    audioConfig =
                        playerResponse
                            .playerConfig
                            ?.audioConfig,
                    videoDetails =
                        playerResponse.videoDetails,
                    playbackTracking =
                        playerResponse.playbackTracking,
                    format = candidate,
                    streamUrl = candidateUrl,
                    streamExpiresInSeconds =
                        expiresInSeconds,
                )
            }
        }

        throwIfGlobalBreakerActive()

        val lastStatus =
            lastPlayerResponse
                ?.playabilityStatus
                ?.status

        throw PlaybackException(
            buildString {
                append(
                    "No playable safe YouTube AUDIO stream",
                )
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
            AudioStreamPolicy.TV_DOWNGRADED ->
                YouTubeClient.TVHTML5_DOWNGRADED
            AudioStreamPolicy.TVHTML5 -> TVHTML5
        }

    private fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        avoidCodecs: Set<String> = emptySet(),
    ): List<PlayerResponse.StreamingData.Format> {
        val audioFormats =
            playerResponse
                .streamingData
                ?.adaptiveFormats
                ?.asSequence()
                ?.filter {
                    it.isAudio && it.bitrate > 0
                }
                ?.filter {
                    it.url != null ||
                        it.signatureCipher != null ||
                        it.cipher != null
                }
                ?.filter { format ->
                    val codec =
                        extractCodec(format.mimeType)
                            ?.lowercase()

                    codec == null ||
                        codec !in avoidCodecs
                }
                ?.toList()
                .orEmpty()

        if (audioFormats.isEmpty()) {
            return emptyList()
        }

        val effectiveQuality =
            when (audioQuality) {
                AudioQuality.AUTO ->
                    if (networkMetered) {
                        AudioQuality.HIGH
                    } else {
                        AudioQuality.HIGHEST
                    }

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
            compareByDescending<PlayerResponse.StreamingData.Format> {
                it.url != null
            }
                .thenByDescending { it.bitrate }
                .thenByDescending {
                    codecRank(
                        extractCodec(it.mimeType),
                    )
                }
                .thenByDescending {
                    it.audioSampleRate ?: 0
                }

        val preferLowerAboveTarget =
            compareByDescending<PlayerResponse.StreamingData.Format> {
                it.url != null
            }
                .thenBy { it.bitrate }
                .thenByDescending {
                    codecRank(
                        extractCodec(it.mimeType),
                    )
                }
                .thenByDescending {
                    it.audioSampleRate ?: 0
                }

        return if (targetBitrateBps == null) {
            audioFormats.sortedWith(preferHigher)
        } else {
            val belowOrEqual =
                audioFormats.filter {
                    it.bitrate <= targetBitrateBps
                }

            if (belowOrEqual.isNotEmpty()) {
                belowOrEqual.sortedWith(preferHigher)
            } else {
                val aboveOrEqual =
                    audioFormats.filter {
                        it.bitrate >= targetBitrateBps
                    }

                if (aboveOrEqual.isNotEmpty()) {
                    aboveOrEqual.sortedWith(
                        preferLowerAboveTarget,
                    )
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
            Timber.tag(logTag).w(
                error,
                "Stream URL validation failed",
            )
            reportException(error)

            StreamProbeResult(
                success = false,
                statusCode = null,
            )
        }
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

    private fun isBotDetectionError(
        reason: String,
    ): Boolean {
        val lower =
            reason.lowercase(Locale.US)

        return "sign in" in lower ||
            "bot" in lower ||
            (
                "confirm" in lower &&
                    "not a" in lower
            ) ||
            (
                "verify" in lower &&
                    "human" in lower
            )
    }

    fun isBotDetectionException(
        error: PlaybackException,
    ): Boolean {
        if (
            isBotDetectionError(
                error.message.orEmpty(),
            )
        ) {
            return true
        }

        var cause: Throwable? = error.cause

        while (cause != null) {
            if (
                isBotDetectionError(
                    cause.message.orEmpty(),
                )
            ) {
                return true
            }

            cause = cause.cause
        }

        return false
    }
}
