@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import timber.log.Timber

/**
 * How much of a stream one request asks for before the next one is opened.
 *
 * A megabyte, so that essentially every audio request is a *part* of a file rather than the whole
 * of it. That distinction turned out to be the one that matters, and it took two captures and
 * eighty-five opens to see it, because at four megabytes only long items were ever split:
 *
 *   asked for part of a file   29 opens   0 refused
 *   asked for a whole file     56 opens   6 refused   (11%)
 *
 * If a bounded request were refused at the same rate, the chance of twenty-nine of them in a row
 * being accepted is under four percent. That is not proof, and the mechanism is still unexplained —
 * the server gives no reason for any refusal, an empty body and its own name in the only header —
 * but it is the first thing in this whole investigation that the numbers actually support.
 *
 * A megabyte rather than two, because the refusals include files of 1.9 MB: a chunk has to be
 * smaller than the files it is meant to split, or those keep going out whole.
 *
 * The cost is more requests per track, and one risk worth naming: a refusal now lands part way
 * through a track instead of before it starts, which interrupts audio rather than delaying it.
 * Twenty-nine for twenty-nine says that should not happen; if it does, this number is where to
 * look.
 */
internal const val AUDIO_CHUNK_BYTES = 1L * 1024 * 1024

/** How many times one slice is asked for before the refusal is handed to the player. */
internal const val CHUNK_OPEN_ATTEMPTS = 3

/** Response codes that mean "not this request" rather than "not this stream". */
internal val REFUSAL_CODES = setOf(403, 410)

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
    private var activeChunkBytes = chunkBytes

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        /*
         * The library's own size wins when it named one. It works this out per client, which is
         * something a single constant here cannot do — and the constant was only ever chosen by
         * counting refusals.
         */
        val chunkBytes =
            (dataSpec.customData as? AudioCdnOpenContext)
                ?.rangeChunkSizeBytes
                ?.takeIf { it > 0L }
                ?: chunkBytes

        /*
         * A URL that already carries its own window is never sliced again.
         *
         * Declaring the bounded-range capability lets the library hand back a link with the window
         * written into the query string. This source reuses one link for every slice, so cutting a
         * pre-windowed link into further slices would ask for bytes outside the window the server
         * agreed to. It has not happened in any capture; if it starts, playback must not quietly
         * truncate, so it is passed through whole and said out loud.
         */
        if (runCatching { dataSpec.uri.getQueryParameter("range") }.getOrNull() != null) {
            Timber.tag("AudioCDN").w("cdn-chunk-skipped reason=url-carries-its-own-range")
            request = null
            opened = true
            return upstream.open(dataSpec)
        }

        if (!shouldChunkAudioRequest(dataSpec.length, chunkBytes)) {
            request = null
            opened = true
            return upstream.open(dataSpec)
        }

        request = dataSpec
        activeChunkBytes = chunkBytes
        nextPosition = dataSpec.position
        bytesLeft = dataSpec.length
        opened = true
        openNextChunk()
        return dataSpec.length
    }

    /**
     * Opens one slice, and asks again if the server refuses that particular request.
     *
     * googlevideo refuses roughly one open in ten with no reason given, and a capture shows the
     * shape of it exactly: on one link, the slice at 47.8 MB was served and the slice at 48.9 MB
     * was refused moments later. The link is not the problem, the individual request is.
     *
     * That rate is survivable for one request per track and fatal for sixty-six. Splitting a long
     * stream multiplied the exposure — a 66 MB item is 66 opens, and at one in ten the chance of
     * meeting a refusal somewhere in it is essentially certain — and no chunk size fixes that: at
     * 4 MB it is still five in six. What fixes it is asking again, because the refusal applies to
     * the request rather than to the link.
     *
     * Three attempts turn one-in-ten into one-in-a-thousand per slice, which is what makes an hour
     * long item survivable. Anything that is not a refusal is passed straight up: a real network
     * failure must not be retried here, where the player cannot see it.
     */
    private fun openNextChunk() {
        val spec = requireNotNull(request)
        chunkLeft = minOf(activeChunkBytes, bytesLeft)
        val chunkSpec =
            spec.buildUpon()
                .setPosition(nextPosition)
                .setLength(chunkLeft)
                .build()

        var attempt = 1
        while (true) {
            try {
                upstream.open(chunkSpec)
                return
            } catch (refused: InvalidResponseCodeException) {
                if (refused.responseCode !in REFUSAL_CODES || attempt >= CHUNK_OPEN_ATTEMPTS) throw refused
                Timber.tag("AudioCDN").w(
                    "cdn-chunk-refused position=%d attempt=%d code=%d; asking again",
                    nextPosition,
                    attempt,
                    refused.responseCode,
                )
                upstream.close()
                attempt += 1
            }
        }
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

    /**
     * Always closes the upstream, whether or not this source finished opening.
     *
     * It used to return early unless the open had completed, and a capture found what that costs.
     * The first slice of a 58 MB item timed out after thirteen seconds; the open threw part way
     * through, so the flag was never set, so this closed nothing — and the upstream was left open.
     * The retry seventeen milliseconds later then opened an already-open source, which Media3
     * rejects outright with an IllegalStateException. One slow network read turned into a track
     * that could not be started at all, twice.
     *
     * Media3 calls close() after a failed open precisely so that state can be cleaned up, and
     * closing a source that was never opened is defined to be safe, so there is nothing for the
     * guard to protect.
     */
    override fun close() {
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
