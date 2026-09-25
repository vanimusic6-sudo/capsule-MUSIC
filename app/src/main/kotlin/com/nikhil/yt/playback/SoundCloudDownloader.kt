package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

internal class SoundCloudDownloader(
    private val trackUrl: String,
    private val mediaId: String,
    private val cache: Cache,
    private val dataSourceFactory: CacheDataSource.Factory,
) : Downloader {
    private val cancellation = SupervisorJob()
    @Volatile private var delegate: Downloader? = null

    override fun download(progressListener: Downloader.ProgressListener?) {
        cancellation.ensureActive()
        val stream = runBlocking(Dispatchers.IO + cancellation) {
            runInterruptible { SoundCloudNewPipe.resolveProgressive(trackUrl) }
        }
        cancellation.ensureActive()
        val downloader = ProgressiveDownloader(
            MediaItem.Builder().setMediaId(mediaId).setUri(stream.url)
                .setCustomCacheKey(mediaId).build(),
            dataSourceFactory,
        )
        delegate = downloader
        downloader.download(progressListener)
    }

    override fun cancel() {
        cancellation.cancel()
        delegate?.cancel()
    }

    override fun remove() {
        delegate?.remove()
        runCatching { cache.removeResource(mediaId) }
    }
}
