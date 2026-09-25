package com.nikhil.yt.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.soundcloud.soundCloudDownloadFile
import com.nikhil.yt.soundcloud.soundCloudDownloadPartFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * File-based SoundCloud downloader modelled after NewPipe/Giga.
 *
 * Key details intentionally match NewPipe:
 * - resolve a fresh progressive stream URL;
 * - probe the resource before transfer;
 * - prefer resumable Range requests;
 * - use "Range: bytes=0-" even for the first fallback GET;
 * - keep partial bytes and re-resolve on transport failures;
 * - consider sequential fallback complete when the server reaches EOF.
 */
internal class SoundCloudDownloader(
    private val context: Context,
    private val request: DownloadRequest,
) : Downloader {
    private val cancellation = SupervisorJob()

    @Volatile
    private var connection: HttpURLConnection? = null

    override fun download(progressListener: Downloader.ProgressListener?) {
        val finalFile = soundCloudDownloadFile(context, request.id)
        val partFile = soundCloudDownloadPartFile(context, request.id)

        finalFile.parentFile?.mkdirs()

        Timber.tag(TAG).i(
            "route id=%s proxy=%s",
            request.id,
            if (YouTube.proxy == null) "direct" else "configured",
        )

        Timber.tag(TAG).i(
            "start id=%s final=%s partialBytes=%d",
            request.id,
            finalFile.name,
            partFile.length(),
        )

        if (finalFile.isFile && finalFile.length() > 0L) {
            val length = finalFile.length()
            Timber.tag(TAG).i("already-complete id=%s bytes=%d", request.id, length)
            progressListener?.onProgress(length, length, 100f)
            return
        }

        var lastFailure: Throwable? = null

        repeat(MAX_RESOLVE_ATTEMPTS) { attempt ->
            cancellation.ensureActive()

            val streamUrl =
                try {
                    runBlocking(Dispatchers.IO + cancellation) {
                        runInterruptible {
                            SoundCloudNewPipe.resolveProgressive(
                                request.uri.toString(),
                            ).url
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    lastFailure = failure
                    Timber.tag(TAG).w(
                        failure,
                        "resolve-failed id=%s attempt=%d",
                        request.id,
                        attempt + 1,
                    )
                    if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                        throw IOException(
                            "Unable to resolve downloadable SoundCloud audio",
                            failure,
                        )
                    }
                    return@repeat
                }

            val host = runCatching { URL(streamUrl).host }.getOrDefault("?")
            Timber.tag(TAG).i(
                "resolved id=%s attempt=%d host=%s partialBytes=%d",
                request.id,
                attempt + 1,
                host,
                partFile.length(),
            )

            try {
                downloadResolved(
                    url = streamUrl,
                    partFile = partFile,
                    progressListener = progressListener,
                )
                cancellation.ensureActive()

                if (!partFile.isFile || partFile.length() <= 0L) {
                    throw IOException("SoundCloud download produced an empty file")
                }

                if (finalFile.exists() && !finalFile.delete()) {
                    throw IOException("Unable to replace old SoundCloud download")
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    if (finalFile.length() != partFile.length()) {
                        finalFile.delete()
                        throw IOException("Unable to commit SoundCloud download")
                    }
                    partFile.delete()
                }

                val length = finalFile.length()
                progressListener?.onProgress(length, length, 100f)
                Timber.tag(TAG).i(
                    "completed id=%s bytes=%d file=%s",
                    request.id,
                    length,
                    finalFile.name,
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                lastFailure = failure
                cancellation.ensureActive()
                Timber.tag(TAG).w(
                    failure,
                    "transfer-failed id=%s attempt=%d partialBytes=%d",
                    request.id,
                    attempt + 1,
                    partFile.length(),
                )
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw failure
                }
                // Keep .part. A fresh NewPipe resolve gets a new signed URL and
                // the next attempt resumes from the exact byte already stored.
            }
        }

        throw IOException("SoundCloud download failed after URL recovery", lastFailure)
    }

    private fun downloadResolved(
        url: String,
        partFile: java.io.File,
        progressListener: Downloader.ProgressListener?,
    ) {
        val probe = probeResource(url)
        val expectedLength = probe.totalLength

        Timber.tag(TAG).i(
            "probe id=%s code=%d length=%d range=%s partialBytes=%d",
            request.id,
            probe.statusCode,
            expectedLength,
            probe.supportsRanges,
            partFile.length(),
        )

        if (expectedLength == 0L) {
            throw IOException("SoundCloud CDN returned an empty resource")
        }

        if (expectedLength > 0L && partFile.length() > expectedLength) {
            Timber.tag(TAG).w(
                "partial-too-large id=%s partial=%d expected=%d; resetting",
                request.id,
                partFile.length(),
                expectedLength,
            )
            RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }

        if (expectedLength > 0L && partFile.length() == expectedLength) {
            progressListener?.onProgress(expectedLength, expectedLength, 100f)
            return
        }

        if (probe.supportsRanges && expectedLength > 0L) {
            downloadRanges(
                url = url,
                partFile = partFile,
                expectedLength = expectedLength,
                progressListener = progressListener,
            )
        } else {
            downloadFallback(
                url = url,
                partFile = partFile,
                expectedLength = expectedLength,
                progressListener = progressListener,
            )
        }
    }

    private data class ProbeResult(
        val statusCode: Int,
        val totalLength: Long,
        val supportsRanges: Boolean,
    )

    /**
     * NewPipe probes with HEAD. Some SoundCloud CDN edges are less consistent
     * for HEAD than GET, so if HEAD is rejected or has no usable length we do a
     * one-byte GET range probe rather than failing a perfectly playable track.
     */
    private fun probeResource(url: String): ProbeResult {
        var headCode = -1
        var headLength = C.LENGTH_UNSET.toLong()

        val head =
            try {
                openConnection(url, method = "HEAD", rangeStart = 0L, rangeEnd = -1L)
            } catch (failure: IOException) {
                Timber.tag(TAG).w(failure, "head-open-failed id=%s", request.id)
                null
            }

        if (head != null) {
            try {
                headCode = head.responseCode
                headLength = totalLength(head)
                Timber.tag(TAG).d(
                    "head id=%s code=%d contentLength=%d contentRange=%s",
                    request.id,
                    headCode,
                    head.contentLengthLong,
                    head.getHeaderField("Content-Range"),
                )

                if (headCode in 200..299 && headLength > 0L) {
                    // NewPipe does a second HEAD near EOF to determine whether
                    // byte ranges are actually honoured.
                    val start = (headLength - 10L).coerceAtLeast(0L)
                    val rangeHead =
                        openConnection(
                            url,
                            method = "HEAD",
                            rangeStart = start,
                            rangeEnd = headLength,
                        )
                    try {
                        val rangeCode = rangeHead.responseCode
                        Timber.tag(TAG).d(
                            "head-range id=%s code=%d contentRange=%s",
                            request.id,
                            rangeCode,
                            rangeHead.getHeaderField("Content-Range"),
                        )
                        return ProbeResult(
                            statusCode = headCode,
                            totalLength = headLength,
                            supportsRanges = rangeCode == HttpURLConnection.HTTP_PARTIAL,
                        )
                    } finally {
                        rangeHead.disconnect()
                        if (connection === rangeHead) connection = null
                    }
                }

                if (headCode !in setOf(
                        HttpURLConnection.HTTP_BAD_METHOD,
                        HttpURLConnection.HTTP_NOT_IMPLEMENTED,
                    ) && headCode !in 200..299
                ) {
                    throw HttpStatusException(headCode)
                }
            } finally {
                head.disconnect()
                if (connection === head) connection = null
            }
        }

        // Robustness fallback: a tiny GET proves the actual media endpoint works
        // and gives Content-Range even on CDNs where HEAD metadata is unreliable.
        val getProbe =
            openConnection(
                url,
                method = "GET",
                rangeStart = 0L,
                rangeEnd = 0L,
            )
        try {
            val code = getProbe.responseCode
            val total = totalLength(getProbe)
            Timber.tag(TAG).d(
                "get-range-probe id=%s code=%d total=%d contentRange=%s",
                request.id,
                code,
                total,
                getProbe.getHeaderField("Content-Range"),
            )

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                runCatching { getProbe.inputStream.read() }
                return ProbeResult(code, total, supportsRanges = total > 0L)
            }

            if (code == HttpURLConnection.HTTP_OK) {
                return ProbeResult(
                    code,
                    total.takeIf { it > 0L } ?: headLength,
                    supportsRanges = false,
                )
            }

            throw HttpStatusException(code)
        } finally {
            getProbe.disconnect()
            if (connection === getProbe) connection = null
        }
    }

    private fun downloadRanges(
        url: String,
        partFile: java.io.File,
        expectedLength: Long,
        progressListener: Downloader.ProgressListener?,
    ) {
        while (partFile.length() < expectedLength) {
            cancellation.ensureActive()

            val start = partFile.length()
            val end = min(start + BLOCK_SIZE - 1L, expectedLength - 1L)
            val conn =
                openConnection(
                    url,
                    method = "GET",
                    rangeStart = start,
                    rangeEnd = end,
                )

            try {
                val code = conn.responseCode
                Timber.tag(TAG).d(
                    "range id=%s start=%d end=%d code=%d contentRange=%s",
                    request.id,
                    start,
                    end,
                    code,
                    conn.getHeaderField("Content-Range"),
                )

                if (code == HttpURLConnection.HTTP_OK) {
                    // Same NewPipe fallback: server ignored Range, so restart
                    // the current resource as a single sequential transfer.
                    downloadFallbackFromOpenConnection(
                        conn = conn,
                        partFile = partFile,
                        expectedLength = expectedLength,
                        progressListener = progressListener,
                        responseHonouredRange = false,
                    )
                    return
                }

                if (code == 416 && start >= expectedLength) {
                    return
                }

                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw HttpStatusException(code)
                }

                val wanted = end - start + 1L
                var written = 0L

                RandomAccessFile(partFile, "rw").use { file ->
                    file.seek(start)
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (written < wanted) {
                            cancellation.ensureActive()
                            val read =
                                input.read(
                                    buffer,
                                    0,
                                    min(buffer.size.toLong(), wanted - written).toInt(),
                                )
                            if (read < 0) break
                            file.write(buffer, 0, read)
                            written += read

                            val done = start + written
                            progressListener?.onProgress(
                                expectedLength,
                                done,
                                percent(done, expectedLength),
                            )
                        }
                    }
                }

                if (written != wanted) {
                    // Preserve what was written, but force a fresh signed URL
                    // before filling the missing bytes.
                    throw IOException(
                        "Short SoundCloud range: got $written of $wanted bytes",
                    )
                }
            } finally {
                conn.disconnect()
                if (connection === conn) connection = null
            }
        }
    }

    /**
     * Mirrors NewPipe's DownloadRunnableFallback:
     * first request explicitly sends Range: bytes=0- (connection-pool
     * workaround); if the CDN returns 200 we restart from zero; if it returns
     * 206 we resume the .part file.
     */
    private fun downloadFallback(
        url: String,
        partFile: java.io.File,
        expectedLength: Long,
        progressListener: Downloader.ProgressListener?,
    ) {
        val start = partFile.length().coerceAtLeast(0L)
        val conn =
            openConnection(
                url,
                method = "GET",
                rangeStart = start,
                rangeEnd = -1L,
            )

        try {
            val code = conn.responseCode
            Timber.tag(TAG).d(
                "fallback id=%s start=%d code=%d contentLength=%d contentRange=%s",
                request.id,
                start,
                code,
                conn.contentLengthLong,
                conn.getHeaderField("Content-Range"),
            )

            if (code !in 200..299 && code != 416) {
                throw HttpStatusException(code)
            }

            if (code == 416 && start > 0L) {
                RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
                throw IOException("SoundCloud resume range rejected with HTTP 416")
            }

            downloadFallbackFromOpenConnection(
                conn = conn,
                partFile = partFile,
                expectedLength = expectedLength,
                progressListener = progressListener,
                responseHonouredRange = code == HttpURLConnection.HTTP_PARTIAL,
            )
        } finally {
            conn.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun downloadFallbackFromOpenConnection(
        conn: HttpURLConnection,
        partFile: java.io.File,
        expectedLength: Long,
        progressListener: Downloader.ProgressListener?,
        responseHonouredRange: Boolean,
    ) {
        val requestedStart = partFile.length().coerceAtLeast(0L)
        val writeStart = if (responseHonouredRange) requestedStart else 0L

        RandomAccessFile(partFile, "rw").use { file ->
            if (!responseHonouredRange) {
                file.setLength(0L)
            }
            file.seek(writeStart)

            var written = writeStart
            conn.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    cancellation.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break

                    file.write(buffer, 0, read)
                    written += read
                    progressListener?.onProgress(
                        expectedLength,
                        written,
                        if (expectedLength > 0L) {
                            percent(written, expectedLength)
                        } else {
                            C.PERCENTAGE_UNSET.toFloat()
                        },
                    )
                }
            }

            /*
             * Important NewPipe behaviour: EOF completes fallback mode.
             * Do not reject a finished transfer solely because a HEAD
             * Content-Length differs from the body length. SoundCloud CDN
             * metadata can be inconsistent across HEAD/GET edges.
             */
            if (written <= 0L) {
                throw IOException("SoundCloud fallback reached EOF without audio bytes")
            }

            Timber.tag(TAG).i(
                "fallback-eof id=%s bytes=%d expected=%d responseCode=%d",
                request.id,
                written,
                expectedLength,
                conn.responseCode,
            )
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        rangeStart: Long = -1L,
        rangeEnd: Long = -1L,
    ): HttpURLConnection {
        cancellation.ensureActive()

        val target = URL(url)
        val rawConnection =
            YouTube.proxy
                ?.let { proxy -> target.openConnection(proxy) }
                ?: target.openConnection()

        val conn =
            (rawConnection as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = method
                setRequestProperty("User-Agent", NEWPIPE_DOWNLOAD_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "*")
                connectTimeout = CONNECT_TIMEOUT_MS

                // NewPipe doesn't install a read timeout for Giga downloads.
                // Let cancellation/DownloadManager own the lifetime instead of
                // aborting a valid slow CDN body after an arbitrary 45 seconds.
                readTimeout = 0

                if (rangeStart >= 0L) {
                    val range =
                        if (rangeEnd >= rangeStart) {
                            "bytes=$rangeStart-$rangeEnd"
                        } else {
                            "bytes=$rangeStart-"
                        }
                    setRequestProperty("Range", range)
                }
            }

        connection = conn
        return conn
    }

    private fun totalLength(conn: HttpURLConnection): Long {
        if (conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            conn.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.takeIf { it != "*" }
                ?.toLongOrNull()
                ?.let { return it }
        }

        return conn.contentLengthLong.takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()
    }

    private fun percent(done: Long, total: Long): Float =
        if (total > 0L) {
            (done.coerceAtMost(total) * 100.0 / total).toFloat()
        } else {
            C.PERCENTAGE_UNSET.toFloat()
        }

    override fun cancel() {
        Timber.tag(TAG).i("cancel id=%s", request.id)
        cancellation.cancel()
        runCatching { connection?.disconnect() }
        connection = null
    }

    override fun remove() {
        Timber.tag(TAG).i("remove id=%s", request.id)
        cancel()
        runCatching { soundCloudDownloadPartFile(context, request.id).delete() }
        runCatching { soundCloudDownloadFile(context, request.id).delete() }
    }

    private class HttpStatusException(
        val statusCode: Int,
    ) : IOException("SoundCloud HTTP $statusCode")

    private companion object {
        const val TAG = "SoundCloudDownload"
        const val MAX_RESOLVE_ATTEMPTS = 5
        const val BLOCK_SIZE = 512 * 1024L
        const val BUFFER_SIZE = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val NEWPIPE_DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
