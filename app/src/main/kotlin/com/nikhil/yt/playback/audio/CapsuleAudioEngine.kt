/*
 * Capsule MUSIC
 *
 * Stable AUDIO boundary. Playback extraction is isolated from Capsule's
 * browse/search InnerTube implementation and uses the maintained InnerTubeX
 * stack for modern player/cipher/client handling.
 *
 * GPL-3.0
 */
package com.nikhil.yt.playback.audio

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioStreamPolicy
import com.nikhil.yt.constants.PlayerStreamClient
import com.nikhil.yt.innertube.CapsuleAnonymousSession
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.response.PlayerResponse
import java.util.concurrent.ConcurrentHashMap

object CapsuleAudioEngine {
    private val lastResolvedClientByVideoId = ConcurrentHashMap<String, String>()

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /** Actual extraction client/profile family used for this song. */
        val streamClient: String? = null,
        /** Required GVS request headers returned by InnerTubeX. */
        val streamHeaders: Map<String, String> = emptyMap(),
    )

    suspend fun prewarm() = CapsuleInnerTubeXPlayer.prewarm()

    fun playbackBlockedExceptionOrNull(): PlaybackException? = CapsulePlaybackSafety.blockedExceptionOrNull()

    fun markRateLimitedFailure() = CapsulePlaybackSafety.markHttpStatusFailure(429)

    fun isRateLimitedException(error: Throwable): Boolean = CapsulePlaybackSafety.isRateLimitedException(error)

    suspend fun refreshAfterStreamRejection(): Boolean =
        CapsuleInnerTubeXPlayer.refreshAfterStreamRejection()

    /**
     * Every normal AUDIO resolve goes through one maintained extraction stack.
     *
     * The legacy parameters remain in the signature temporarily so older call
     * sites and diagnostics do not need a coordinated migration, but they can
     * no longer route playback into the old resolver. Explicit web choices
     * reach InnerTubeX unchanged; retired policies normalize to visionOS.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.VISIONOS,
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): Result<PlaybackData> {
        CapsulePlaybackSafety.blockedExceptionOrNull()?.let { return Result.failure(it) }

        return CapsuleInnerTubeXPlayer
            .playerResponseForPlayback(
                videoId = videoId,
                playlistId = playlistId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                streamPolicy = streamPolicy.normalizedForPlayback(),
            )
            .map { resolved ->
                resolved.streamClient
                    .substringBefore('@')
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { lastResolvedClientByVideoId[videoId] = it }

                PlaybackData(
                    audioConfig = resolved.audioConfig,
                    videoDetails = resolved.videoDetails,
                    playbackTracking = resolved.playbackTracking,
                    format = resolved.format,
                    streamUrl = resolved.streamUrl,
                    streamExpiresInSeconds = resolved.streamExpiresInSeconds,
                    streamClient = resolved.streamClient,
                    streamHeaders = resolved.streamHeaders,
                )
            }
            .onFailure(CapsulePlaybackSafety::observeFailure)
    }

    /** Metadata-only compatibility request. This never resolves a stream URL. */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        CapsulePlaybackSafety.blockedExceptionOrNull()?.let { return Result.failure(it) }
        return CapsuleAnonymousSession.player(
            videoId = videoId,
            client = YouTubeClient.VISIONOS,
            signatureTimestamp = null,
        ).onFailure(CapsulePlaybackSafety::observeFailure)
    }

    /**
     * InnerTubeX does not keep Capsule's signed URL cache. The active URL cache
     * is owned by MusicService and is removed there before calling this hook.
     */
    fun invalidateCachedStreamUrls(videoId: String) = Unit

    fun clearTrackClientFailures(videoId: String) {
        lastResolvedClientByVideoId.remove(videoId)
        CapsuleInnerTubeXPlayer.clearTrackClientFailures(videoId)
    }

    fun clearStreamClientFailures() {
        lastResolvedClientByVideoId.clear()
        CapsuleInnerTubeXPlayer.clearStreamClientFailures()
    }

    fun clearPlaybackSafetyState() {
        lastResolvedClientByVideoId.clear()
        CapsuleInnerTubeXPlayer.clearPlaybackState()
        CapsulePlaybackSafety.clear()
    }

    fun onNetworkChanged() {
        lastResolvedClientByVideoId.clear()
        CapsuleInnerTubeXPlayer.onNetworkChanged()
        CapsulePlaybackSafety.clear()
    }

    /**
     * A stream/source rejection is local to one song + extraction client.
     * HTTP 429 additionally opens the global cooldown instead of rotating more
     * identities. If Media3 cannot recover a client from the signed URL, use
     * the exact client remembered from the successful InnerTubeX extraction.
     */
    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
        CapsulePlaybackSafety.markHttpStatusFailure(httpStatusCode)
        val resolvedClient =
            clientKey
                ?.substringBefore('@')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: lastResolvedClientByVideoId[videoId]

        CapsuleInnerTubeXPlayer.markStreamClientFailed(
            videoId = videoId,
            clientName = resolvedClient,
        )
        lastResolvedClientByVideoId.remove(videoId)
    }

    /**
     * Kept only for binary/source compatibility with older diagnostics. Normal
     * Capsule AUDIO playback no longer uses PlayerStreamClient routing.
     */
    fun markPreferredClientFailed(
        videoId: String,
        client: PlayerStreamClient,
        httpStatusCode: Int?,
    ) {
        CapsulePlaybackSafety.markHttpStatusFailure(httpStatusCode)
        CapsuleInnerTubeXPlayer.markStreamClientFailed(
            videoId = videoId,
            clientName = client.name,
        )
        lastResolvedClientByVideoId.remove(videoId)
    }

    fun markBotDetectionFailure(reason: String? = null) {
        CapsulePlaybackSafety.markBotDetectionFailure(reason)
    }

    fun isBotDetectionException(error: PlaybackException): Boolean =
        CapsulePlaybackSafety.isBotDetectionException(error)
}
