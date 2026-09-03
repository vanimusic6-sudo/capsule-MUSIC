/*
 * Capsule MUSIC
 * Stable YouTube AUDIO stream resolver.
 *
 * Playback deliberately behaves like a normal single-session player:
 * - one reviewed client identity per logical resolve;
 * - no automatic client rotation after a rejected request;
 * - no pre-flight GVS probe before Media3 opens the stream;
 * - 429/bot-check opens a global breaker instead of escalating traffic;
 * - 403 from a signed media URL is treated as refreshable, not as a reason to
 *   quarantine the only healthy client for the whole track;
 * - secondary loudness lookup never starts extra player requests.
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
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.MWEB
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5_DOWNGRADED
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.VISIONOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_EMBEDDED
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.pages.NewPipeUtils
import io.ktor.client.plugins.ResponseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"

    private const val GLOBAL_BREAKER_MS = 10 * 60 * 1000L

    /*
     * A logical AUDIO resolve is intentionally one player request. Media3 is
     * the stream validator: doing a separate range probe doubled GVS traffic
     * and made rapid skipping much noisier than a normal player.
     */
    private const val MAX_PLAYER_REQUESTS_PER_RESOLVE = 1
    private const val MAX_STREAM_PROBES_PER_RESOLVE = 0

    private const val FRESH_CACHE_SAFETY_MS = 30_000L
    private const val MAX_STREAM_CACHE_ENTRIES = 800
    private const val LOUDNESS_MISS_TTL_MS = 30 * 60 * 1000L

    @Volatile
    private var globalPlaybackBreakerUntilMs: Long = 0L

    @Volatile
    private var globalPlaybackBreakerReason: String? = null

    private val MAIN_CLIENT: YouTubeClient = VISIONOS

    private data class CachedStreamUrl(
        val url: String,
        val expiresAtMs: Long,
    )

    data class LoudnessMetadata(
        val loudnessDb: Double?,
        val perceptualLoudnessDb: Double?,
    )

    private data class CachedLoudnessMetadata(
        val value: LoudnessMetadata?,
        val expiresAtMs: Long,
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

    private val streamUrlCache = ConcurrentHashMap<String, CachedStreamUrl>()
    private val loudnessMetadataCache = ConcurrentHashMap<String, CachedLoudnessMetadata>()

    fun invalidateCachedStreamUrls(videoId: String) {
        val prefix = "$videoId:"
        streamUrlCache.keys.removeIf { it.startsWith(prefix) }
    }

    /*
     * Kept for source compatibility with recovery code. A media-URL 403 is not
     * evidence that VisionOS itself is unusable for this song, so there is no
     * per-track client quarantine to clear anymore.
     */
    fun clearTrackClientFailures(videoId: String) = Unit

    fun clearPlaybackSafetyState() {
        streamUrlCache.clear()
        loudnessMetadataCache.clear()
        globalPlaybackBreakerUntilMs = 0L
        globalPlaybackBreakerReason = null
        CapsuleAnonymousSession.reset()
        NewPipeUtils.clearPlayerCaches()
    }

    fun onNetworkChanged() {
        streamUrlCache.clear()
        loudnessMetadataCache.clear()
        globalPlaybackBreakerUntilMs = 0L
        globalPlaybackBreakerReason = null
        CapsuleAnonymousSession.reset()
        NewPipeUtils.resetForNetworkChange()

        Timber.tag(logTag).i(
            "Default network changed; cleared network-bound playback state",
        )
    }

    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
        if (httpStatusCode == 429) {
            tripGlobalBreaker("YouTube returned HTTP 429")
            return
        }

        if (httpStatusCode == 403 || httpStatusCode == 401) {
            Timber.tag(logTag).w(
                "Signed AUDIO stream rejected for %s (%s, HTTP %s); URL will be refreshed with the same client",
                videoId,
                normalizeStreamClientKey(clientKey).ifBlank { "unknown-client" },
                httpStatusCode,
            )
        }
    }

    fun markPreferredClientFailed(
        videoId: String,
        client: PlayerStreamClient,
        httpStatusCode: Int?,
    ) {
        val effective =
            if (client == PlayerStreamClient.TVHTML5) TVHTML5 else VISIONOS

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

    private fun normalizeStreamClientKey(clientKey: String?): String =
        clientKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()

    private fun effectiveIdentityKey(client: YouTubeClient): String =
        normalizeStreamClientKey("${client.clientName}@${client.clientVersion}")

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
            ((globalPlaybackBreakerUntilMs - System.currentTimeMillis()) / 1000L)
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
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.AUTO_SAFE,
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): Result<PlaybackData> =
        runCatching {
            throwIfGlobalBreakerActive()
            val budget = ResolveRequestBudget()

            try {
                playerResponseForPlaybackOnce(
                    videoId = videoId,
                    playlistId = playlistId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    streamPolicy = streamPolicy,
                    networkMetered = networkMetered,
                    avoidCodecs = avoidCodecs,
                    budget = budget,
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
        budget: ResolveRequestBudget,
    ): PlaybackData {
        throwIfGlobalBreakerActive()

        val client =
            preferredClient(
                streamPolicy = streamPolicy,
                legacyPreference = preferredStreamClient,
            )

        if (!budget.tryConsumePlayerRequest()) {
            throw PlaybackException(
                "YouTube AUDIO request budget exhausted",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        Timber.tag(logTag).i(
            "Resolving AUDIO once with %s for videoId=%s playlistId=%s",
            effectiveIdentityKey(client),
            videoId,
            playlistId,
        )

        val signatureTimestamp =
            if (client.useSignatureTimestamp) getSignatureTimestampOrNull(videoId) else null

        val responseResult =
            CapsuleAnonymousSession.player(
                videoId = videoId,
                client = client,
                signatureTimestamp = signatureTimestamp,
            )

        val playerFailure = responseResult.exceptionOrNull()
        if (playerFailure != null) {
            val kind = classifyThrowableFailure(playerFailure)
            throwPlayerFailure(
                videoId = videoId,
                client = client,
                kind = kind,
                cause = playerFailure,
                reason = playerFailure.message.orEmpty(),
            )
        }

        val playerResponse = responseResult.getOrThrow()
        val status = playerResponse.playabilityStatus.status
        if (status != "OK") {
            val reason = playerResponse.playabilityStatus.reason.orEmpty()
            val kind =
                YouTubeFailureClassifier.classify(
                    playabilityStatus = status,
                    text = reason,
                )
            throwPlayerFailure(
                videoId = videoId,
                client = client,
                kind = kind,
                cause = null,
                reason = reason.ifBlank { status.orEmpty() },
            )
        }

        val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
        val candidates =
            selectAudioFormatCandidates(
                playerResponse = playerResponse,
                audioQuality = audioQuality,
                networkMetered = isMetered,
                avoidCodecs = avoidCodecs,
            )

        if (candidates.isEmpty()) {
            throw PlaybackException(
                "No playable YouTube AUDIO format",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        val expectedDurationMs =
            playerResponse.videoDetails
                ?.lengthSeconds
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.times(1000L)

        for (candidate in candidates) {
            if (expectedDurationMs != null && isLikelyPreview(candidate, expectedDurationMs)) {
                continue
            }

            val cacheKey = buildCacheKey(videoId, candidate.itag, client)
            val now = System.currentTimeMillis()
            val cached = streamUrlCache[cacheKey]
            if (cached != null) {
                if (cached.expiresAtMs > now + FRESH_CACHE_SAFETY_MS) {
                    val expiresInSeconds =
                        ((cached.expiresAtMs - now) / 1000L)
                            .coerceAtLeast(1L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()

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

            val candidateUrl =
                findUrlOrNull(
                    format = candidate,
                    videoId = videoId,
                    client = client,
                ) ?: continue

            val expiresInSeconds = playerResponse.streamingData?.expiresInSeconds ?: 300
            streamUrlCache[cacheKey] =
                CachedStreamUrl(
                    url = candidateUrl,
                    expiresAtMs = System.currentTimeMillis() + expiresInSeconds * 1000L,
                )
            pruneStreamUrlCache()

            Timber.tag(logTag).i(
                "AUDIO URL ready with %s itag=%d; Media3 will validate the stream",
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

        throw PlaybackException(
            "No playable YouTube AUDIO stream URL",
            null,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }

    private fun throwPlayerFailure(
        videoId: String,
        client: YouTubeClient,
        kind: YouTubeFailureKind,
        cause: Throwable?,
        reason: String,
    ): Nothing {
        when (kind) {
            YouTubeFailureKind.RATE_LIMITED -> {
                tripGlobalBreaker("YouTube returned HTTP 429")
            }

            YouTubeFailureKind.BOT_CHECK -> {
                tripGlobalBreaker(
                    reason.takeIf { it.isNotBlank() }
                        ?.let { "YouTube bot-check: ${it.take(160)}" }
                        ?: "YouTube requested a bot check",
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

        val message =
            when (kind) {
                YouTubeFailureKind.RATE_LIMITED -> "YouTube rate limited playback"
                YouTubeFailureKind.BOT_CHECK -> "YouTube requested a bot check"
                YouTubeFailureKind.PERMANENT -> "This track is unavailable"
                YouTubeFailureKind.LOGIN_REQUIRED -> "This track requires a signed-in playback session"
                YouTubeFailureKind.AGE_RESTRICTED -> "This track is age restricted"
                YouTubeFailureKind.FORBIDDEN -> "YouTube rejected the playback request"
                else -> reason.ifBlank { "YouTube AUDIO playback request failed" }
            }

        throw PlaybackException(
            message,
            cause,
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
            signatureTimestamp = null,
        )

    /**
     * Do not start another player request merely to obtain ReplayGain metadata.
     * The playback response already contributes loudness when YouTube includes
     * it; if it does not, unity gain is safer than multiplying request volume.
     */
    suspend fun resolveLoudnessForNormalization(videoId: String): LoudnessMetadata? {
        val now = System.currentTimeMillis()
        loudnessMetadataCache[videoId]
            ?.takeIf { it.expiresAtMs > now }
            ?.let { return it.value }

        loudnessMetadataCache[videoId] =
            CachedLoudnessMetadata(
                value = null,
                expiresAtMs = now + LOUDNESS_MISS_TTL_MS,
            )
        Timber.tag(logTag).d(
            "Skipping secondary loudness player request for %s",
            videoId,
        )
        return null
    }

    private fun preferredClient(
        streamPolicy: AudioStreamPolicy,
        legacyPreference: PlayerStreamClient,
    ): YouTubeClient =
        when (streamPolicy) {
            /* AUTO_SAFE is intentionally deterministic. */
            AudioStreamPolicy.AUTO_SAFE,
            AudioStreamPolicy.VISIONOS,
            -> VISIONOS

            AudioStreamPolicy.WEB_EMBEDDED -> WEB_EMBEDDED
            AudioStreamPolicy.WEB -> WEB
            AudioStreamPolicy.MWEB -> MWEB
            AudioStreamPolicy.IOS -> YouTubeClient.IOS
            AudioStreamPolicy.IOS_MUSIC -> YouTubeClient.IOS_MUSIC
            AudioStreamPolicy.TV_DOWNGRADED -> TVHTML5_DOWNGRADED
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
                    it.url != null || it.signatureCipher != null || it.cipher != null
                }
                .filter { format ->
                    val codec = extractCodec(format.mimeType)?.lowercase()
                    codec == null || codec !in avoidCodecs
                }
                .toList()

        if (audioFormats.isEmpty()) return emptyList()

        val effectiveQuality =
            when (audioQuality) {
                AudioQuality.AUTO -> if (networkMetered) AudioQuality.HIGH else AudioQuality.HIGHEST
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

    private fun extractCodec(mimeType: String): String? {
        val match = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return null
        return match.groupValues
            .getOrNull(1)
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
    }

    private fun codecRank(codec: String?): Int =
        when {
            codec.isNullOrBlank() -> 0
            codec.contains("opus", ignoreCase = true) -> 3
            codec.contains("mp4a", ignoreCase = true) -> 2
            else -> 1
        }

    private fun isLikelyPreview(
        format: PlayerResponse.StreamingData.Format,
        expectedDurationMs: Long,
    ): Boolean {
        val approx = format.approxDurationMs?.toLongOrNull() ?: return false
        if (expectedDurationMs < 90_000L) return false

        return approx in 1L..minOf(90_000L, (expectedDurationMs * 9L) / 10L)
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? =
        NewPipeUtils
            .getSignatureTimestamp(videoId)
            .onFailure {
                Timber.tag(logTag).w(it, "Failed to get signature timestamp")
                reportException(it)
            }
            .getOrNull()

    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
    ): String? {
        val url =
            NewPipeUtils
                .getStreamUrl(format, videoId, client)
                .onFailure {
                    Timber.tag(logTag).w(it, "Failed to get stream URL")
                    reportException(it)
                }
                .getOrNull()
                ?: return null

        return StreamClientUtils.patchClientVersion(
            url = url,
            clientVersion = client.clientVersion,
        )
    }

    private fun buildCacheKey(
        videoId: String,
        itag: Int,
        client: YouTubeClient,
    ): String = "$videoId:${effectiveIdentityKey(client)}:$itag"

    private fun classifyThrowableFailure(throwable: Throwable): YouTubeFailureKind {
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

    internal fun shouldTryNextClientForTest(kind: YouTubeFailureKind): Boolean = false

    private fun isBotDetectionError(reason: String): Boolean =
        YouTubeFailureClassifier.classify(text = reason) == YouTubeFailureKind.BOT_CHECK

    fun isBotDetectionException(error: PlaybackException): Boolean {
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
}
