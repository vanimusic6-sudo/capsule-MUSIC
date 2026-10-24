package com.nikhil.yt.playback.audio

import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Resolve the format before resuming bytes; report success only for a complete cache entry. */
internal class ResolvingAudioDownloader(
    private val mediaId: String,
    private val cache: Cache,
    private val resolve: suspend () -> CapsuleAudioEngine.PlaybackData,
    private val dataSource: (CapsuleAudioEngine.PlaybackData) -> CacheDataSource.Factory,
    private val recover: (CapsuleAudioEngine.PlaybackData, IOException) -> Boolean = { _, _ -> false },
    private val createDownloader: (CapsuleAudioEngine.PlaybackData) -> Downloader = { playback ->
        ProgressiveDownloader(
            MediaItem.Builder().setUri(playback.streamUrl).setCustomCacheKey(mediaId).build(),
            dataSource(playback),
        )
    },
) : Downloader {
    private val cancellation = SupervisorJob()
    @Volatile private var delegate: Downloader? = null
    private var resolved: CapsuleAudioEngine.PlaybackData? = null
    private var expiresAtNs = 0L
    private var recoveries = 0

    override fun download(progressListener: Downloader.ProgressListener?) {
        try {
            checkActive()
            if (AudioCacheIdentity.isComplete(cache, mediaId)) {
                completed(progressListener)
                return
            }
            while (true) {
                checkActive()
                val playback = resolved?.takeIf { System.nanoTime() < expiresAtNs }
                    ?: runBlocking(Dispatchers.IO + cancellation) { resolve() }.also {
                        resolved = it
                        expiresAtNs = System.nanoTime() +
                            (it.streamExpiresInSeconds.toLong() - 15L).coerceAtLeast(0L) * 1_000_000_000L
                    }
                checkActive()
                AudioCacheIdentity.prepareDownload(
                    cache, mediaId, AudioCacheIdentity.key(mediaId, playback), playback.format.contentLength,
                )
                val downloader = createDownloader(playback)
                delegate = downloader
                try {
                    checkActive()
                    downloader.download { length, bytes, percent ->
                        // ProgressiveDownloader can reach EOF on a truncated response.
                        progressListener?.onProgress(length, bytes, percent.coerceAtMost(99f))
                    }
                    checkActive()
                    AudioCacheIdentity.requireCompleteDownload(cache, mediaId, playback.format.contentLength)
                    completed(progressListener)
                    return
                } catch (failure: IOException) {
                    checkActive()
                    if (recoveries >= MAX_RECOVERIES || !recover(playback, failure)) throw failure
                    recoveries++
                    resolved = null
                } finally {
                    delegate = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw InterruptedException("Audio download cancelled").apply { initCause(cancelled) }
        }
    }

    private fun checkActive() {
        cancellation.ensureActive()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio download interrupted")
    }

    private fun completed(listener: Downloader.ProgressListener?) {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
        listener?.onProgress(length, length, 100f)
    }

    override fun cancel() {
        cancellation.cancel()
        delegate?.cancel()
    }

    override fun remove() { AudioCacheIdentity.remove(cache, mediaId) }

    private companion object { const val MAX_RECOVERIES = 2 }
}
