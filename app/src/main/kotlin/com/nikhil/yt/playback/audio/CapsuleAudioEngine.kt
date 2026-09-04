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

object CapsuleAudioEngine {
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

    /**
     * Every normal AUDIO resolve goes through one maintained extraction stack.
     *
     * The legacy parameters remain in the signature temporarily so older call
     * sites and diagnostics do not need a coordinated migration, but they can
     * no longer route playback into the old resolver. Obsolete stored policies
     * are normalized to AUTO_SAFE before they reach InnerTubeX.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.AUTO_SAFE,
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
            .onFailure(CapsulePlaybackSafety::observeFailure)
            .map { resolved ->
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
    }

    /** Metadata-only compatibility request. This never resolves a stream URL. */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        CapsuleAnonymousSession.player(
            videoId = videoId,
            client = YouTubeClient.VISIONOS,
            signatureTimestamp = null,
        )

    /**
     * InnerTubeX does not keep Capsule's signed URL cache. The active URL cache
     * is owned by MusicService and is removed there before calling this hook.
     */
    fun invalidateCachedStreamUrls(videoId: String) = Unit

    fun clearTrackClientFailures(videoId: String) {
        CapsuleInnerTubeXPlayer.clearTrackClientFailures(videoId)
    }

    fun clearPlaybackSafetyState() {
        CapsuleInnerTubeXPlayer.clearPlaybackState()
        CapsulePlaybackSafety.clear()
    }

    fun onNetworkChanged() {
        CapsuleInnerTubeXPlayer.onNetworkChanged()
        CapsulePlaybackSafety.clear()
    }

    /**
     * A stream/source rejection is local to one song + extraction client.
     * HTTP 429 additionally opens the global cooldown instead of rotating more
     * identities.
     */
    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
        CapsulePlaybackSafety.markHttpStatusFailure(httpStatusCode)
        CapsuleInnerTubeXPlayer.markStreamClientFailed(
            videoId = videoId,
            clientName = clientKey,
        )
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
    }

    fun markBotDetectionFailure(reason: String? = null) {
        CapsulePlaybackSafety.markBotDetectionFailure(reason)
    }

    fun isBotDetectionException(error: PlaybackException): Boolean =
        CapsulePlaybackSafety.isBotDetectionException(error)
}
