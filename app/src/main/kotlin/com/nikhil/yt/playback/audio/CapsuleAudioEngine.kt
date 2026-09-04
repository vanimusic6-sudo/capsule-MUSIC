/*
 * Capsule MUSIC
 *
 * Stable AUDIO boundary. Playback extraction is now isolated from Capsule's
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
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.utils.YTPlayerUtils

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
        /*
         * MWEB/iOS compatibility switches are kept on the old resolver for now
         * because Capsule exposes them as explicit legacy choices. Normal
         * playback, Web Remix, Embedded and TV are all handled by InnerTubeX.
         */
        if (
            streamPolicy == AudioStreamPolicy.MWEB ||
            streamPolicy == AudioStreamPolicy.IOS ||
            streamPolicy == AudioStreamPolicy.IOS_MUSIC
        ) {
            return YTPlayerUtils
                .playerResponseForPlayback(
                    videoId = videoId,
                    playlistId = playlistId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    streamPolicy = streamPolicy,
                    networkMetered = networkMetered,
                    avoidCodecs = avoidCodecs,
                )
                .map { resolved ->
                    PlaybackData(
                        audioConfig = resolved.audioConfig,
                        videoDetails = resolved.videoDetails,
                        playbackTracking = resolved.playbackTracking,
                        format = resolved.format,
                        streamUrl = resolved.streamUrl,
                        streamExpiresInSeconds = resolved.streamExpiresInSeconds,
                    )
                }
        }

        return CapsuleInnerTubeXPlayer
            .playerResponseForPlayback(
                videoId = videoId,
                playlistId = playlistId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                streamPolicy = streamPolicy,
            )
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

    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        YTPlayerUtils.playerResponseForMetadata(
            videoId = videoId,
            playlistId = playlistId,
        )

    fun invalidateCachedStreamUrls(videoId: String) {
        YTPlayerUtils.invalidateCachedStreamUrls(videoId)
    }

    fun clearTrackClientFailures(videoId: String) {
        CapsuleInnerTubeXPlayer.clearTrackClientFailures(videoId)
        YTPlayerUtils.clearTrackClientFailures(videoId)
    }

    fun clearPlaybackSafetyState() {
        CapsuleInnerTubeXPlayer.clearPlaybackState()
        YTPlayerUtils.clearPlaybackSafetyState()
    }

    fun onNetworkChanged() {
        CapsuleInnerTubeXPlayer.onNetworkChanged()
        YTPlayerUtils.onNetworkChanged()
    }

    /**
     * A stream/source rejection is local to one song + extraction client.
     * 429/bot protection remains global through the existing safety breaker.
     */
    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
        CapsuleInnerTubeXPlayer.markStreamClientFailed(
            videoId = videoId,
            clientName = clientKey,
        )
        YTPlayerUtils.markStreamClientFailed(
            videoId = videoId,
            clientKey = clientKey,
            httpStatusCode = httpStatusCode,
        )
    }

    fun markPreferredClientFailed(
        videoId: String,
        client: PlayerStreamClient,
        httpStatusCode: Int?,
    ) {
        YTPlayerUtils.markPreferredClientFailed(
            videoId = videoId,
            client = client,
            httpStatusCode = httpStatusCode,
        )
    }

    fun markBotDetectionFailure(reason: String? = null) {
        YTPlayerUtils.markBotDetectionFailure(reason)
    }

    fun isBotDetectionException(error: PlaybackException): Boolean =
        YTPlayerUtils.isBotDetectionException(error)
}
