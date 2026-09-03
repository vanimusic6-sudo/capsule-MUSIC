/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.models.response

import com.nikhil.yt.innertube.models.ResponseContext
import com.nikhil.yt.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PlayerResponse with [com.nikhil.yt.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
data class PlayerResponse(
    val responseContext: ResponseContext = ResponseContext(),
    val playabilityStatus: PlayabilityStatus,
    val playerConfig: PlayerConfig? = null,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
    @SerialName("playbackTracking")
    val playbackTracking: PlaybackTracking? = null,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String? = null,
    )

    @Serializable
    /*
     * audioConfig is optional. Not every client identity includes it, and a
     * required field here would reject the whole PlayerResponse over missing
     * loudness metadata, exactly as VideoDetails used to.
     */
    data class PlayerConfig(
        val audioConfig: AudioConfig? = null,
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double? = null,
            val perceptualLoudnessDb: Double? = null,
        )
    }

    @Serializable
    data class StreamingData(
        val formats: List<Format>? = null,
        val adaptiveFormats: List<Format> = emptyList(),
        val expiresInSeconds: Int = 300,
    ) {
        @Serializable
        data class Format(
            val itag: Int,
            val url: String? = null,
            val mimeType: String,
            val bitrate: Int,
            val width: Int? = null,
            val height: Int? = null,
            val contentLength: Long? = null,
            val quality: String,
            val fps: Int? = null,
            val qualityLabel: String? = null,
            val averageBitrate: Int? = null,
            val audioQuality: String? = null,
            val approxDurationMs: String? = null,
            val audioSampleRate: Int? = null,
            val audioChannels: Int? = null,
            val loudnessDb: Double? = null,
            /* Sometimes present per format when playerConfig omits it. */
            val perceptualLoudnessDb: Double? = null,
            val lastModified: Long? = null,
            val signatureCipher: String? = null,
            val cipher: String? = null,
        ) {
            val isAudio: Boolean
                get() = width == null
        }
    }

    @Serializable
    /*
     * Only videoId is guaranteed. Several client identities answer with a
     * trimmed videoDetails (no viewCount, sometimes no author or thumbnail),
     * and with non-null fields kotlinx rejects the ENTIRE PlayerResponse over
     * one missing string. That threw away a perfectly playable stream and
     * burned the client out of the rotation. Metadata is optional here; the
     * stream is the point.
     */
    data class VideoDetails(
        val videoId: String,
        val title: String? = null,
        val author: String? = null,
        val channelId: String? = null,
        val lengthSeconds: String? = null,
        val musicVideoType: String? = null,
        val viewCount: String? = null,
        val thumbnail: Thumbnails? = null,
    )

    @Serializable
    data class PlaybackTracking(
        @SerialName("videostatsPlaybackUrl")
        val videostatsPlaybackUrl: VideostatsPlaybackUrl? = null,
        @SerialName("videostatsWatchtimeUrl")
        val videostatsWatchtimeUrl: VideostatsWatchtimeUrl? = null,
        @SerialName("atrUrl")
        val atrUrl: AtrUrl? = null,
    ) {
        @Serializable
        data class VideostatsPlaybackUrl(
            @SerialName("baseUrl")
            val baseUrl: String? = null,
        )
        @Serializable
        data class VideostatsWatchtimeUrl(
            @SerialName("baseUrl")
            val baseUrl: String? = null,
        )
        @Serializable
        data class AtrUrl(
            @SerialName("baseUrl")
            val baseUrl: String? = null,
        )
    }
}
