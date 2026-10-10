@file:Suppress("UnsafeOptInUsageError")

/*
 * Capsule MUSIC — bounded CDN reads for VIDEO only.
 *
 * The existing Media3 ProgressiveMediaSource, VIDEO cache and AUDIO pipeline
 * remain unchanged. The wrapping DataSource delivers one continuous byte
 * stream to Media3, but requests at most 4 MiB from the VIDEO CDN at a time.
 *
 * For an unknown-length resource, the server's Content-Range response gives
 * the real file size. If the CDN ignores range requests, fall back to the
 * original source behaviour instead of silently truncating the video.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

internal const val CAPSULE_VIDEO_CHUNK_BYTES = 4L * 1024 * 1024

internal class CapsuleVideoChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = CAPSULE_VIDEO_CHUNK_BYTES,
) : DataSource {
    init {
        require(chunkBytes > 0L)
    }

    private data class ByteRange(
        val start: Long,
        val end: Long,
        val total: Long,
    ) {
        val size: Long get() = end - start + 1L
    }

    private var original: DataSpec? = null
    private var nextPosition = 0L
    private var bytesLeft = 0L
    private var chunkLeft = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(!opened) { "VIDEO DataSource is already open" }
        opened = true

        // Preserve native short-stream playback and pre-windowed signed URLs.
        // A URL with its own range query cannot be sliced beyond that window.
        if (
            (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length <= chunkBytes) ||
            runCatching { dataSpec.uri.getQueryParameter("range") }.getOrNull() != null
        ) {
            return upstream.open(dataSpec)
        }

        val firstLength =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                chunkBytes
            } else {
                minOf(chunkBytes, dataSpec.length)
            }

        val firstSpec =
            dataSpec.buildUpon()
                .setLength(firstLength)
                .build()

        try {
            upstream.open(firstSpec)
            val range = parseRange(upstream.responseHeaders)

            if (range == null || range.start != dataSpec.position) {
                // Some hosts ignore Range. Rather than inventing a file size or
                // making Media3 interpret the first 4 MiB as the entire file,
                // revert to its unmodified streaming behaviour for this URL.
                upstream.close()
                return upstream.open(dataSpec)
            }

            val available = range.total - dataSpec.position
            if (available <= 0L) {
                throw EOFException("VIDEO CDN range starts past the resource")
            }
            val expected =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    available
                } else {
                    dataSpec.length
                }

            if (expected > available) {
                throw EOFException("VIDEO CDN cannot satisfy requested resource length")
            }
            if (range.size > firstLength || range.size > expected) {
                throw IOException("VIDEO CDN returned unexpected first byte range")
            }

            original = dataSpec
            nextPosition = dataSpec.position
            bytesLeft = expected
            chunkLeft = range.size
            return expected
        } catch (failure: Throwable) {
            runCatching { upstream.close() }
            original = null
            throw failure
        }
    }

    private fun openNextChunk() {
        val request = requireNotNull(original)
        val length = minOf(bytesLeft, chunkBytes)
        val nextSpec =
            request.buildUpon()
                .setPosition(nextPosition)
                .setLength(length)
                .build()

        upstream.open(nextSpec)
        val range = parseRange(upstream.responseHeaders)
        if (range == null || range.start != nextPosition ||
            range.size > length || range.size <= 0L
        ) {
            throw IOException("VIDEO CDN did not honor continuation byte range")
        }
        chunkLeft = range.size
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (original == null) return upstream.read(buffer, offset, length)
        if (bytesLeft == 0L) return C.RESULT_END_OF_INPUT

        if (chunkLeft == 0L) {
            upstream.close()
            openNextChunk()
        }

        val count = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (count == C.RESULT_END_OF_INPUT) {
            throw EOFException(
                "VIDEO CDN ended a bounded request early (remaining=$chunkLeft)",
            )
        }

        if (count > 0) {
            nextPosition += count
            bytesLeft -= count
            chunkLeft -= count
        }
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    override fun close() {
        opened = false
        original = null
        nextPosition = 0L
        bytesLeft = 0L
        chunkLeft = 0L
        // Media3 must be able to close after a failed open as well.
        upstream.close()
    }

    private fun parseRange(headers: Map<String, List<String>>): ByteRange? {
        val header =
            headers.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()
                ?.trim()
                ?: return null
        val match = CONTENT_RANGE_REGEX.matchEntire(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || total <= end) return null
        return ByteRange(start, end, total)
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkBytes: Long = CAPSULE_VIDEO_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CapsuleVideoChunkedDataSource(
                upstreamFactory.createDataSource(),
                chunkBytes,
            )
    }

    private companion object {
        val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    }
}
