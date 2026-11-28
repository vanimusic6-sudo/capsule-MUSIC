package com.nikhil.yt.playback.audio

import com.nikhil.yt.playback.withAudioDownloadSlotAfterAdmission
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.IOException

/** Resolve the format before resuming bytes; report success only for a complete cache entry. */
internal class ResolvingAudioDownloader(
    private val mediaId: String,
    private val cache: Cache,
    private val resolve: suspend () -> CapsuleAudioEngine.PlaybackData,
    private val dataSource: (CapsuleAudioEngine.PlaybackData) -> CacheDataSource.Factory,
    private val saveArtwork: suspend () -> Unit = {},
    private val recover: (CapsuleAudioEngine.PlaybackData, IOException) -> Boolean = { _, _ -> false },
    private val createDownloader: (CapsuleAudioEngine.PlaybackData) -> Downloader = { playback ->
        ProgressiveDownloader(
            MediaItem.Builder().setUri(playback.streamUrl).setCustomCacheKey(mediaId).build(),
            dataSource(playback),
        )
    },
    private val awaitAdmission: suspend () -> Unit = {},
    private val createCachedDownloader: () -> Downloader? = { null },
) : Downloader {
    private val cancellation = SupervisorJob()
    @Volatile private var delegate: Downloader? = null
    private var recoveries = 0
    private var terminalRefusal: IOException? = null

    override fun download(progressListener: Downloader.ProgressListener?) {
        try {
            runBlocking(Dispatchers.IO + cancellation) {
                coroutineScope {
                    var artwork: Job? = null
                    try {
                        val startArtwork = {
                            if (artwork == null) artwork = launch(Dispatchers.IO) {
                                // An optional cover request cannot turn a completed
                                // music download into a failed Media3 transfer.
                                try {
                                    saveArtwork()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Existing artwork-repair flow can fetch this later.
                                }
                            }
                            Unit
                        }
                        val copied = downloadFromPlaybackCache(progressListener, startArtwork)
                        if (!copied) withAudioDownloadSlotAfterAdmission(
                            awaitAdmission = {
                                // A complete cache-only item never needs to ask YouTube.
                                if (!AudioCacheIdentity.isComplete(cache, mediaId)) awaitAdmission()
                            },
                        ) {
                            downloadWithSlot({ length, bytes, percent ->
                                progressListener?.onProgress(length, bytes, percent.coerceAtMost(99f))
                            }, startArtwork)
                        }
                        // Audio's shared network slot is free. Still await artwork
                        // before marking the Media3 download complete.
                        artwork?.join()
                        completed(progressListener)
                    } finally { artwork?.cancel() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw InterruptedException("Audio download cancelled while waiting for a slot").apply { initCause(cancelled) }
        }
    }

    private fun downloadFromPlaybackCache(
        progressListener: Downloader.ProgressListener?, startArtwork: () -> Unit,
    ): Boolean {
        checkActive()
        if (AudioCacheIdentity.isComplete(cache, mediaId)) {
            // No network slot, cooldown wait or second resolve for an already
            // complete offline file. Artwork may still need repair.
            startArtwork()
            return true
        }
        try {
            val cached = createCachedDownloader() ?: return false
            delegate = cached
            try {
                checkActive()
                cached.download { length, bytes, percent ->
                    progressListener?.onProgress(length, bytes, percent.coerceAtMost(99f))
                }
                checkActive()
                AudioCacheIdentity.requireCompleteDownload(cache, mediaId, null)
                startArtwork()
                return true
            } finally { delegate = null }
        } catch (evicted: IOException) {
            checkActive()
            // An LRU eviction can race the copy. Retain verified partial bytes;
            // the normal resolver will bind/check the format before resuming.
            return false
        }
    }

    private suspend fun downloadWithSlot(progressListener: Downloader.ProgressListener?, startArtwork: () -> Unit) {
        try {
            checkActive()
            if (AudioCacheIdentity.isComplete(cache, mediaId)) {
                startArtwork()
                return
            }
            terminalRefusal?.let { throw it }
            while (true) {
                checkActive()
                // The caller owns the session-, network- and quality-bound URL cache.
                val playback = resolve()
                startArtwork()
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
                    return
                } catch (failure: IOException) {
                    checkActive()
                    val status = generateSequence(failure as Throwable?) { it.cause }.take(12)
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode
                    if (failure.audioAccessStopOrNull() != null) throw failure
                    if (status == 401 || status == 403 || status == 429) {
                        recover(playback, failure)
                        if (status != 429) terminalRefusal = failure
                        throw failure
                    }
                    if (recoveries >= MAX_RECOVERIES || !recover(playback, failure)) {
                        if (status == 410) {
                            if (recoveries >= MAX_RECOVERIES) recover(playback, failure)
                            terminalRefusal = failure
                        }
                        throw failure
                    }
                    recoveries++
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

    private companion object { const val MAX_RECOVERIES = 1 }
}
