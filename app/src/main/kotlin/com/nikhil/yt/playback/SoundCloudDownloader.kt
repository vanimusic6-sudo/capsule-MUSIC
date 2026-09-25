package com.nikhil.yt.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.soundcloud.soundCloudDownloadFile
import com.nikhil.yt.soundcloud.soundCloudDownloadPartFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * SoundCloud downloads deliberately do NOT use Media3 cache.
 *
 * NewPipe resolves a progressive URL, probes the resource, and writes the bytes
 * to a real file with HTTP Range support. Capsule now follows that model too.
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

        if (finalFile.isFile && finalFile.length() > 0L) {
            val length = finalFile.length()
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
                Log.i(TAG, "completed id=${request.id} bytes=$length file=${finalFile.name}")
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                lastFailure = failure
                cancellation.ensureActive()
                Log.w(
                    TAG,
                    "transfer failed id=${request.id} attempt=${attempt + 1} partial=${partFile.length()}",
                    failure,
                )
                if (attempt == MAX_RESOLVE_ATTEMPTS - 1) {
                    throw failure
                }
            }
        }

        throw IOException("SoundCloud download failed after URL recovery", lastFailure)
    }

    private fun downloadResolved(
        url: String,
        partFile: java.io.File,
        progressListener: Downloader.ProgressListener?,
    ) {
        val probe = openConnection(url, method = "HEAD")
        val expectedLength =
            try {
                ensureSuccessful(probe)
                totalLength(probe)
            } finally {
                probe.disconnect()
                if (connection === probe) connection = null
            }

        if (expectedLength == 0L) {
            throw IOException("SoundCloud CDN returned an empty resource")
        }

        if (expectedLength > 0L && partFile.length() > expectedLength) {
            RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }

        if (expectedLength > 0L && partFile.length() == expectedLength) {
            progressListener?.onProgress(expectedLength, expectedLength, 100f)
            return
        }

        val supportsRanges =
            if (expectedLength > 0L) {
                val start = (expectedLength - 10L).coerceAtLeast(0L)
                val end = expectedLength - 1L
                val rangeProbe =
                    openConnection(
                        url,
                        method = "HEAD",
                        rangeStart = start,
                        rangeEnd = end,
                    )
                try {
                    rangeProbe.responseCode == HttpURLConnection.HTTP_PARTIAL
                } finally {
                    rangeProbe.disconnect()
                    if (connection === rangeProbe) connection = null
                }
            } else {
                false
            }

        if (!supportsRanges) {
            downloadWhole(url, partFile, expectedLength, progressListener)
            return
        }

        downloadRanges(url, partFile, expectedLength, progressListener)
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
                if (code == HttpURLConnection.HTTP_OK) {
                    downloadWholeFromOpenConnection(
                        conn = conn,
                        partFile = partFile,
                        expectedLength = expectedLength,
                        progressListener = progressListener,
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

    private fun downloadWhole(
        url: String,
        partFile: java.io.File,
        expectedLength: Long,
        progressListener: Downloader.ProgressListener?,
    ) {
        val conn = openConnection(url, method = "GET")
        try {
            ensureSuccessful(conn)
            downloadWholeFromOpenConnection(
                conn = conn,
                partFile = partFile,
                expectedLength = expectedLength.takeIf { it > 0L } ?: totalLength(conn),
                progressListener = progressListener,
            )
        } finally {
            conn.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun downloadWholeFromOpenConnection(
        conn: HttpURLConnection,
        partFile: java.io.File,
        expectedLength: Long,
        progressListener: Downloader.ProgressListener?,
    ) {
        RandomAccessFile(partFile, "rw").use { file ->
            file.setLength(0L)
            file.seek(0L)

            var written = 0L
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

            if (expectedLength > 0L && written != expectedLength) {
                throw IOException(
                    "Incomplete SoundCloud response: got $written expected $expectedLength",
                )
            }
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        rangeStart: Long = -1L,
        rangeEnd: Long = -1L,
    ): HttpURLConnection {
        cancellation.ensureActive()
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = method
                setRequestProperty("User-Agent", NEWPIPE_DOWNLOAD_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "*")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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

    private fun ensureSuccessful(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw HttpStatusException(code)
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
        cancellation.cancel()
        runCatching { connection?.disconnect() }
        connection = null
    }

    override fun remove() {
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
        const val READ_TIMEOUT_MS = 45_000
        const val NEWPIPE_DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
