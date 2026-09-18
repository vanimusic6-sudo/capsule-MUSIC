@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** How much of a stream one request asks for before the next one is opened. */
internal const val AUDIO_CHUNK_BYTES = 4L * 1024 * 1024

/**
 * Whether a request for [length] bytes should be split, given a chunk size of [chunkBytes].
 *
 * A length that is not yet known cannot be split safely, because the end of a chunk and the end of
 * the file would be indistinguishable.
 */
internal fun shouldChunkAudioRequest(
    length: Long,
    chunkBytes: Long = AUDIO_CHUNK_BYTES,
): Boolean = length != C.LENGTH_UNSET.toLong() && length > chunkBytes

/**
 * Asks googlevideo for a long stream a few megabytes at a time.
 *
 * A single request for a whole file is served fast for a few seconds and then paced, and the pace
 * it settles at is well under what the stream costs. That is invisible on a song, which arrives
 * whole inside the fast part — a three megabyte track buffers seventy-seven seconds ahead and is
 * simply done. It is fatal on a forty minute episode: a capture of one shows around 2.4 to 6 kB/s
 * sustained against the 13.4 kB/s the stream needs, so the buffer drains as fast as it fills and
 * playback settles into a cycle of playing eleven seconds and waiting fourteen.
 *
 * The same capture shows the way out. Every time a *new* request was opened — including one opened
 * by accident, when a connection failed and the stream was re-resolved — the next stretch played
 * cleanly, and one of them ran a full minute without a stall. Each request gets its own fast
 * opening; only staying in one gets paced.
 *
 * So a long read is split. Each chunk is an ordinary bounded request, the seam between them is
 * invisible to everything above, and nothing changes for a file that fits in one chunk — which is
 * every song, so the case that already works is not touched at all.
 */
internal class AudioChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = AUDIO_CHUNK_BYTES,
) : DataSource {
    private var request: DataSpec? = null
    private var nextPosition = 0L
    private var bytesLeft = 0L
    private var chunkLeft = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!shouldChunkAudioRequest(dataSpec.length, chunkBytes)) {
            request = null
            opened = true
            return upstream.open(dataSpec)
        }

        request = dataSpec
        nextPosition = dataSpec.position
        bytesLeft = dataSpec.length
        openNextChunk()
        opened = true
        return dataSpec.length
    }

    private fun openNextChunk() {
        val spec = requireNotNull(request)
        chunkLeft = minOf(chunkBytes, bytesLeft)
        upstream.open(
            spec.buildUpon()
                .setPosition(nextPosition)
                .setLength(chunkLeft)
                .build(),
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (request == null) return upstream.read(buffer, offset, length)
        if (bytesLeft == 0L) return C.RESULT_END_OF_INPUT

        if (chunkLeft == 0L) {
            upstream.close()
            openNextChunk()
        }

        val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            /*
             * A chunk that ends early ends the stream. Treating it as a seam and opening the next
             * one would turn a truncated download into an endless loop of empty requests.
             */
            bytesLeft = 0L
            return C.RESULT_END_OF_INPUT
        }

        nextPosition += read
        bytesLeft -= read
        chunkLeft -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (!opened) return
        opened = false
        request = null
        upstream.close()
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkBytes: Long = AUDIO_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioChunkedDataSource(upstreamFactory.createDataSource(), chunkBytes)
    }
}
