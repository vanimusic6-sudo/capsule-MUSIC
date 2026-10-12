/*
 * Capsule MUSIC — direct VIDEO-only MediaSource, isolated from the AUDIO
 * ResolvingDataSource / queue / stream retry machinery.
 * GPL-3.0
 */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import timber.log.Timber

/**
 * No synthetic capsule-video:// URL is ever opened, and no new VIDEO metadata
 * request happens from Media3's loader threads. The extractor has resolved the
 * signed URLs before this factory receives a MediaItem.
 *
 * Both muxed and adaptive children use ONLY videoCache + VIDEO transport.
 */
internal class CapsuleVideoDirectMediaSourceFactory(
    upstream: DataSource.Factory,
    private val hlsUpstream: DataSource.Factory = upstream,
    private val findResolved: (String) -> YouTubeVideoResolver.ResolvedVideo?,
) : MediaSource.Factory {

    private val progressive = ProgressiveMediaSource.Factory(upstream)
        .setContinueLoadingCheckIntervalBytes(64 * 1024)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val videoId = mediaItem.localConfiguration?.uri?.lastPathSegment.orEmpty()
        check(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            "VIDEO source requires a previously matched video ID"
        }
        val stream = checkNotNull(
            mediaItem.localConfiguration?.tag as? YouTubeVideoResolver.ResolvedVideo
                ?: findResolved(videoId),
        ) {
            "Resolved VIDEO URL expired before source preparation"
        }
        check(stream.expiresAtMs > System.currentTimeMillis() + 45_000L) {
            "Resolved VIDEO signed URL is already expired"
        }
        if (stream.delivery == CapsuleVideoDelivery.HLS) {
            Timber.tag("CapsuleVideo").i("source-direct segmented clip=%s maxHeight=%s", videoId, stream.maxHeight)
            return HlsMediaSource.Factory(hlsUpstream)
                .setPlaylistParserFactory(CapsuleHlsPlaylistParserFactory(stream.maxHeight))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
                .createMediaSource(mediaItem.buildUpon().setUri(stream.videoStreamUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build())
        }
        val isMuxed = stream.audioStreamUrl.isNullOrBlank()
        val videoItem = mediaItem.buildUpon()
            .setUri(stream.videoStreamUrl)
            .setCustomCacheKey(
                if (isMuxed) {
                    "$CAPSULE_VIDEO_CACHE_PREFIX$videoId:${stream.videoFormat.itag}"
                } else {
                    "$CAPSULE_VIDEO_STREAM_CACHE_PREFIX" +
                        "video:$videoId:${stream.videoFormat.itag}"
                },
            )
            .build()
        val videoSource = progressive.createMediaSource(videoItem)
        val audioUrl = stream.audioStreamUrl
        val audioFormat = stream.audioFormat
        if (audioUrl.isNullOrBlank() || audioFormat == null) {
            Timber.tag("CapsuleVideo").i(
                "source-direct muxed clip=%s itag=%s", videoId, stream.videoFormat.itag,
            )
            return videoSource
        }

        val audioItem = MediaItem.Builder()
            .setMediaId("${mediaItem.mediaId}:video-audio")
            .setUri(audioUrl)
            .setCustomCacheKey(
                "$CAPSULE_VIDEO_STREAM_CACHE_PREFIX" +
                    "audio:$videoId:${audioFormat.itag}",
            )
            .build()
        Timber.tag("CapsuleVideo").i(
            "source-direct adaptive clip=%s videoItag=%s audioItag=%s",
            videoId, stream.videoFormat.itag, audioFormat.itag,
        )
        return MergingMediaSource(
            true,
            true,
            videoSource,
            progressive.createMediaSource(audioItem),
        )
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        progressive.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        progressive.setLoadErrorHandlingPolicy(policy)
    }
}
