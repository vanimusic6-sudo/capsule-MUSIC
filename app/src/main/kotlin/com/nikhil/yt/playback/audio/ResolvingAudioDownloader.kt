package com.nikhil.yt.playback.audio

import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** Resolve and validate the format BEFORE Media3 reads or resumes the download cache. */
internal class ResolvingAudioDownloader(
    private val mediaId: String,
    private val cache: Cache,
    private val resolve: suspend () -> CapsuleAudioEngine.PlaybackData,
    private val dataSource: (CapsuleAudioEngine.PlaybackData) -> CacheDataSource.Factory,
) : Downloader {
    private val cancellation = SupervisorJob()
    @Volatile private var delegate: Downloader? = null

    override fun download(progressListener: Downloader.ProgressListener?) {
        cancellation.ensureActive()
        if (AudioCacheIdentity.isComplete(cache, mediaId)) {
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
            progressListener?.onProgress(length, length, 100f)
            return
        }
        val playback = runBlocking(Dispatchers.IO + cancellation) { resolve() }
        cancellation.ensureActive()
        AudioCacheIdentity.prepareDownload(
            cache, mediaId, AudioCacheIdentity.key(mediaId, playback), playback.format.contentLength,
        )
        val downloader = ProgressiveDownloader(
            MediaItem.Builder().setUri(playback.streamUrl).setCustomCacheKey(mediaId).build(),
            dataSource(playback),
        )
        delegate = downloader
        try {
            cancellation.ensureActive()
            downloader.download(progressListener)
        } finally {
            delegate = null
        }
    }

    override fun cancel() {
        cancellation.cancel()
        delegate?.cancel()
    }

    override fun remove() { cache.removeResource(mediaId) }
}
