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
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
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
    private val upstream: DataSource.Factory,
    private val hlsUpstream: DataSource.Factory = upstream,
    private val findResolved: (String) -> YouTubeVideoResolver.ResolvedVideo?,
) : MediaSource.Factory {

    private val progressive = ProgressiveMediaSource.Factory(upstream)
        .setContinueLoadingCheckIntervalBytes(64 * 1024)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
    private var errorPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(1)
    private var drmProvider: DrmSessionManagerProvider? = null

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
        val segmentedUpstream = CapsuleVideoTransportFactory(hlsUpstream, stream.userAgent)
        if (stream.delivery == CapsuleVideoDelivery.DASH) {
            val xml = checkNotNull(stream.dashManifest) { "Indexed VIDEO manifest missing" }
            check(xml.length in 1..64 * 1024) { "Indexed VIDEO manifest exceeds bounds" }
            val manifest = DashManifestParser().parse(mediaItem.localConfiguration!!.uri, xml.byteInputStream())
            check(!manifest.dynamic && manifest.periodCount == 1 && manifest.durationMs > 0L)
            val keys = mutableMapOf<String, String>()
            for (set in manifest.getPeriod(0).adaptationSets) {
                check(set.type == C.TRACK_TYPE_VIDEO || set.type == C.TRACK_TYPE_AUDIO)
                check(set.representations.size in 1..32)
                for (representation in set.representations) {
                    val format = representation.format
                    check(format.id?.toIntOrNull()?.let { it > 0 } == true)
                    if (set.type == C.TRACK_TYPE_VIDEO) check(format.height in 1..stream.maxHeight)
                    val init = representation.initializationUri
                    val index = representation.indexUri
                    check(init != null && index != null && init.start >= 0L && init.length in 1..128 * 1024L &&
                        index.start >= init.start + init.length && index.length in 1..128 * 1024L)
                    check(init.referenceUri.isEmpty() && index.referenceUri.isEmpty())
                    for (base in representation.baseUrls) {
                        val uri = Uri.parse(base.url)
                        check(uri.scheme == "https" && uri.host?.endsWith(".googlevideo.com") == true && uri.userInfo == null)
                        val role = if (set.type == C.TRACK_TYPE_VIDEO) "video" else "audio"
                        keys[base.url] = "$CAPSULE_VIDEO_STREAM_CACHE_PREFIX$role:$videoId:${format.id}"
                    }
                }
            }
            check(manifest.getPeriod(0).adaptationSets.any { it.type == C.TRACK_TYPE_VIDEO } &&
                manifest.getPeriod(0).adaptationSets.any { it.type == C.TRACK_TYPE_AUDIO })
            Timber.tag("CapsuleVideo").i("source-direct indexed DASH clip=%s maxHeight=%s", videoId, stream.maxHeight)
            return DashMediaSource.Factory(CapsuleVideoTransportFactory(hlsUpstream, stream.userAgent, keys))
                .setLoadErrorHandlingPolicy(errorPolicy).apply { drmProvider?.let(::setDrmSessionManagerProvider) }
                .createMediaSource(manifest, mediaItem.buildUpon().setMimeType(MimeTypes.APPLICATION_MPD).setCustomCacheKey(null).build())
        }
        if (stream.delivery == CapsuleVideoDelivery.HLS) {
            Timber.tag("CapsuleVideo").i("source-direct segmented clip=%s maxHeight=%s", videoId, stream.maxHeight)
            return HlsMediaSource.Factory(segmentedUpstream)
                .setPlaylistParserFactory(CapsuleHlsPlaylistParserFactory(stream.maxHeight))
                .setLoadErrorHandlingPolicy(errorPolicy)
                .apply { drmProvider?.let(::setDrmSessionManagerProvider) }
                .createMediaSource(mediaItem.buildUpon().setUri(stream.videoStreamUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build())
        }
        val progressive = if (stream.userAgent.isNullOrBlank()) progressive else
            ProgressiveMediaSource.Factory(CapsuleVideoTransportFactory(upstream, stream.userAgent))
                .setContinueLoadingCheckIntervalBytes(64 * 1024).setLoadErrorHandlingPolicy(errorPolicy)
                .apply { drmProvider?.let(::setDrmSessionManagerProvider) }
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

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        drmProvider = provider
        progressive.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        errorPolicy = policy
        progressive.setLoadErrorHandlingPolicy(policy)
    }
}

/** Apply before CacheDataSource: signed URL refreshes retain each representation's byte cache. */
internal class CapsuleVideoTransportFactory(
    private val upstream: DataSource.Factory,
    private val userAgent: String?,
    private val cacheKeys: Map<String, String> = emptyMap(),
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private val source = upstream.createDataSource()
        override fun open(dataSpec: DataSpec): Long {
            val mapped = dataSpec.buildUpon().apply {
                cacheKeys[dataSpec.uri.toString()]?.let(::setKey)
                userAgent?.takeIf { it.isNotBlank() }?.let {
                    setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("User-Agent" to it))
                }
            }.build()
            return source.open(mapped)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
        override fun getUri(): Uri? = source.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
        override fun close() = source.close()
    }
}
