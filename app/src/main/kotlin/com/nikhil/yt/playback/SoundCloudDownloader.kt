package com.nikhil.yt.playback

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader

internal class SoundCloudDownloader(
    private val request: DownloadRequest,
    private val dataSourceFactory: CacheDataSource.Factory,
) : Downloader {
    @Volatile
    private var delegate: Downloader? = null

    private fun createDelegate(): Downloader {
        val mediaItem = request.toMediaItem()
        return if (request.mimeType == MimeTypes.APPLICATION_M3U8) {
            HlsDownloader.Factory(dataSourceFactory).create(mediaItem)
        } else {
            ProgressiveDownloader(
                mediaItem,
                dataSourceFactory,
            )
        }
    }

    override fun download(
        progressListener: Downloader.ProgressListener?,
    ) {
        val active = createDelegate()
        delegate = active
        active.download(progressListener)
    }

    override fun cancel() {
        delegate?.cancel()
    }

    override fun remove() {
        (delegate ?: createDelegate()).remove()
    }
}
