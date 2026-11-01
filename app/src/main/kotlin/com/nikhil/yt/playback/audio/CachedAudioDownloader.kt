@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.audio

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader

/** Copy a complete, format-identified playback entry without any resolver or network upstream. */
internal fun createCachedAudioDownloader(mediaId: String, source: Cache, target: Cache): Downloader? {
    val key = AudioCacheIdentity.completeKey(source, mediaId) ?: return null
    val length = ContentMetadata.getContentLength(source.getContentMetadata(key))
    if (length <= 0L) return null
    AudioCacheIdentity.prepareDownload(target, mediaId, key, length)
    val localOnly = DataSource.Factory {
        ResolvingDataSource(
            CacheDataSource.Factory().setCache(source)
                .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
                .createDataSource(),
        ) { spec -> spec.buildUpon().setKey(key).build() }
    }
    val downloader = ProgressiveDownloader(
        MediaItem.Builder().setUri("capsule-cache:$mediaId").setCustomCacheKey(mediaId).build(),
        CacheDataSource.Factory().setCache(target).setUpstreamDataSourceFactory(localOnly),
    )
    return object : Downloader by downloader {
        override fun download(progressListener: Downloader.ProgressListener?) {
            downloader.download(progressListener)
            // Keep the original verified size even if a concurrent eviction or
            // truncated disk file makes CacheWriter observe an earlier EOF.
            AudioCacheIdentity.requireCompleteDownload(target, mediaId, length)
        }
    }
}
