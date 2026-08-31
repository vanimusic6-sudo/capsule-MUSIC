/*
 * Capsule MUSIC
 *
 * Capsule Audio Engine — Phase 1 façade.
 *
 * Purpose:
 * - give MusicService one stable AUDIO entry point;
 * - keep the currently working YTPlayerUtils implementation unchanged;
 * - make it possible to replace the resolver internals later without making
 *   MusicService depend on those details;
 * - preserve the existing safety behaviour for 403 / 429 / bot-checks.
 *
 * This phase deliberately does NOT add client rotation, token spoofing,
 * CAPTCHA bypasses, or runtime code updates.
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

/**
 * Stable AUDIO boundary for the rest of the application.
 *
 * Phase 1 is intentionally a thin adapter over the already-working
 * [YTPlayerUtils]. In later phases the implementation can move behind this
 * object without changing MusicService again.
 */
object CapsuleAudioEngine {

    /**
     * Engine-owned result type.
     *
     * MusicService currently needs these exact fields, so keeping them here
     * removes the need for the service to depend on YTPlayerUtils.PlaybackData.
     */
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
        streamPolicy: AudioStreamPolicy =
            AudioStreamPolicy.AUTO_SAFE,
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): Result<PlaybackData> =
        YTPlayerUtils
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
                    streamExpiresInSeconds =
                        resolved.streamExpiresInSeconds,
                )
            }

    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        YTPlayerUtils.playerResponseForMetadata(
            videoId = videoId,
            playlistId = playlistId,
        )

    /**
     * A stream URL may be stale while the client itself remains valid.
     * This keeps the existing per-track failure state intact.
     */
    fun invalidateCachedStreamUrls(videoId: String) {
        YTPlayerUtils.invalidateCachedStreamUrls(videoId)
    }

    /**
     * Explicit/manual retry hook for one track.
     */
    fun clearTrackClientFailures(videoId: String) {
        YTPlayerUtils.clearTrackClientFailures(videoId)
    }

    /**
     * Full explicit reset. Do not use this to work around a live YouTube block.
     */
    fun clearPlaybackSafetyState() {
        YTPlayerUtils.clearPlaybackSafetyState()
    }

    /**
     * Preserve current semantics:
     * - 403 -> exact track/client cooldown
     * - 429 -> global AUDIO breaker
     */
    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
    ) {
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

    fun isBotDetectionException(
        error: PlaybackException,
    ): Boolean =
        YTPlayerUtils.isBotDetectionException(error)
}
