package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * SoundCloud download path intentionally follows NewPipe's behaviour:
 * - persist the canonical track permalink, never a short-lived CDN URL;
 * - download only PROGRESSIVE_HTTP audio;
 * - resolve a fresh signed URL when a download attempt starts;
 * - re-resolve on transport failure while keeping the same Media3 cache key,
 *   so already cached ranges can be reused.
 */
internal class SoundCloudDownloader(
    private val request: DownloadRequest,
    private val cache: Cache,
    private val dataSourceFactory: CacheDataSource.Factory,
) : Downloader {
    private val cancellation = SupervisorJob()

    @Volatile
    private var delegate: Downloader? = null

    override fun download(
        progressListener: Downloader.ProgressListener?,
    ) {
        var lastFailure: Throwable? = null

        repeat(MAX_RESOLVE_ATTEMPTS) { attempt ->
            cancellation.ensureActive()

            val stream = try {
                runBlocking(Dispatchers.IO + cancellation) {
                    runInterruptible {
                        SoundCloudNewPipe.resolveProgressive(
                            request.uri.toString(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw IOException(
                        "Unable to resolve downloadable SoundCloud audio",
                        failure,
                    )
                }
                return@repeat
            }

            cancellation.ensureActive()

            val active = ProgressiveDownloader(
                MediaItem.Builder()
                    .setMediaId(request.id)
                    .setUri(stream.url)
                    .setCustomCacheKey(request.id)
                    .build(),
                dataSourceFactory,
            )
            delegate = active

            try {
                active.download(progressListener)
                return
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw failure
                }
                // The signed SoundCloud CDN URL can expire or be replaced.
                // The next iteration extracts a fresh progressive URL.
            }
        }

        throw IOException(
            "SoundCloud download failed after URL recovery",
            lastFailure,
        )
    }

    override fun cancel() {
        cancellation.cancel()
        delegate?.cancel()
    }

    override fun remove() {
        delegate?.remove()
        runCatching {
            cache.removeResource(request.id)
        }
    }

    private companion object {
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}
