package com.nikhil.yt.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * SoundCloud download path intentionally mirrors NewPipe's recovery model:
 * - persist the canonical track permalink, never a short-lived CDN URL;
 * - resolve only a progressive HTTP stream when the download actually runs;
 * - write bytes directly into Capsule's persistent download cache under one
 *   stable key;
 * - on transport failure, resolve a fresh signed URL and continue filling the
 *   same cache entry.
 *
 * We intentionally use CacheWriter instead of ProgressiveDownloader here.
 * CacheWriter exposes the actual cache write operation, so a successful
 * network read cannot silently turn into a "download" that never persisted.
 */
internal class SoundCloudDownloader(
    private val request: DownloadRequest,
    private val cache: Cache,
    private val dataSourceFactory: CacheDataSource.Factory,
) : Downloader {
    private val cancellation = SupervisorJob()

    @Volatile
    private var writer: CacheWriter? = null

    override fun download(
        progressListener: Downloader.ProgressListener?,
    ) {
        val cacheKey = request.customCacheKey ?: request.id
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
                Log.w(
                    TAG,
                    "resolve failed id=${request.id} attempt=${attempt + 1}",
                    failure,
                )
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw IOException(
                        "Unable to resolve downloadable SoundCloud audio",
                        failure,
                    )
                }
                return@repeat
            }

            cancellation.ensureActive()

            var requestLength = C.LENGTH_UNSET.toLong()
            val active =
                CacheWriter(
                    // Match Media3's ProgressiveDownloader semantics for an
                    // offline job: block on a cache hole instead of bypassing
                    // it, and allow the resource to be committed in fragments.
                    dataSourceFactory.createDataSourceForDownloading(),
                    DataSpec.Builder()
                        .setUri(stream.url)
                        .setKey(cacheKey)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                        .build(),
                    null,
                ) { length, bytesCached, _ ->
                    requestLength = length
                    progressListener?.onProgress(
                        length,
                        bytesCached,
                        if (length > 0L) {
                            bytesCached.coerceAtMost(length) * 100f / length
                        } else {
                            C.PERCENTAGE_UNSET.toFloat()
                        },
                    )
                }

            writer = active

            try {
                active.cache()
                cancellation.ensureActive()

                val cachedBytes =
                    cache.getCachedSpans(cacheKey)
                        .sumOf { span -> span.length }

                if (cachedBytes <= 0L) {
                    throw IOException(
                        "SoundCloud transfer finished without persistent cache data",
                    )
                }

                if (
                    requestLength > 0L &&
                    requestLength != C.LENGTH_UNSET.toLong() &&
                    !cache.isCached(cacheKey, 0L, requestLength)
                ) {
                    throw IOException(
                        "SoundCloud cache is incomplete: cached=$cachedBytes expected=$requestLength",
                    )
                }

                Log.i(
                    TAG,
                    "completed id=${request.id} cachedBytes=$cachedBytes length=$requestLength",
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                lastFailure = failure
                cancellation.ensureActive()
                Log.w(
                    TAG,
                    "cache write failed id=${request.id} attempt=${attempt + 1}",
                    failure,
                )
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw failure
                }
                // CacheWriter keeps already written ranges under the stable key.
                // A fresh signed URL on the next attempt fills only missing bytes.
            } finally {
                if (writer === active) {
                    writer = null
                }
            }
        }

        throw IOException(
            "SoundCloud download failed after URL recovery",
            lastFailure,
        )
    }

    override fun cancel() {
        writer?.cancel()
        cancellation.cancel()
    }

    override fun remove() {
        writer?.cancel()
        runCatching {
            cache.removeResource(request.customCacheKey ?: request.id)
        }
    }

    private companion object {
        const val TAG = "SoundCloudDownload"
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}
