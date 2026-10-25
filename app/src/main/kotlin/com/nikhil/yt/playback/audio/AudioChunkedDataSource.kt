@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/**
 * How much of a stream one request asks for before the next one is opened.
 *
 * A megabyte, chosen when it looked as though bounded requests were the ones that never got
 * refused:
 *
 *   asked for part of a file   29 opens   0 refused
 *   asked for a whole file     56 opens   6 refused   (11%)
 *
 * That reading did not survive. Two later captures split the same way and the difference is gone:
 *
 *                              12:41                 15:38
 *   first slice of a track     6 of 40  (15.0%)      3 of 44  (6.8%)
 *   a later slice              1 of 55  ( 1.8%)      5 of 86  (5.8%)
 *
 * A later slice is a bounded request by definition, and in the second capture it is refused at
 * very nearly the rate of the first one. Twenty-nine for twenty-nine was a small sample landing
 * the lucky way. The one split that has held across both captures is not about the request at all
 * but about the connection carrying it -- 15 refusals in 120 first requests on a new socket, 0 in
 * 120 on a reused one -- and that is also the thing this size actually moves, in the wrong
 * direction: a megabyte means more requests per track, and under HTTP/1.1 more requests mean more
 * sockets.
 *
 * So the number is kept for the reason below this comment, which is throttling, and no longer for
 * refusals. Raising it would cut the request count and with it the socket count; it would also
 * put more of a track behind a single paced response. That trade wants measuring, not guessing --
 * see AudioCdnSessionStats, whose summary now carries requestsPerSocket and the protocol mix.
 *
 * The cost is more requests per track, and one risk worth naming: a refusal can land part way
 * through a track instead of before it starts, which interrupts audio rather than delaying it.
 * The 15:38 capture has five of those; all five recovered on retry.
 */
internal const val AUDIO_CHUNK_BYTES = 1L * 1024 * 1024

// Media3's Matroska/WebM extractor requested ~19,000 tiny read() calls for
// 1 MiB of Opus, versus ~3,000 for MP4/AAC in the same phone capture.
// Readahead below coalesces ONLY upstream Opus network reads. It never
// changes the signed URL, seek offsets, CDN range size or cached audio bytes.
internal const val OPUS_READAHEAD_BYTES = 8 * 1024

internal fun isOpusChunkKey(key: String?): Boolean {
    if (key?.startsWith("capsule:audio:") != true) return false
    val itag = key.substringBeforeLast(':').substringAfterLast(':').toIntOrNull()
    return itag == 249 || itag == 250 || itag == 251
}

/** Initial CDN open plus ONE same-link retry before the service tries the next client. */
internal const val CHUNK_OPEN_ATTEMPTS = 2
// Always respect the extractor's bounded audio ranges. The temporary
// whole-short-song optimization skipped the staged first read and turned a
// 3–4MB ordinary file into one long CDN request, reintroducing pacing/seek risks.

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
 * Adaptive ranges ONLY for measured slow socket reads, never total time
 * between open() and close(). Media3 can suspend reads for a full buffer:
 * cdn-close elapsedMs and deliveredToLoaderBps are not network throughput.
 * Without actual slow read() samples, preserve InnerTubeX's chunk sizing.
 */
internal class AudioCdnChunkRatePolicy(
    private val initialChunkBytes: Long,
    private val reducedChunkBytes: Long = 512L * 1024,
    private val minRequestedBytes: Int = 8 * 1024,
    private val slowReadMs: Long = 250L,
    private val slowBps: Long = 48L * 1024,
    private val recoveredBps: Long = 160L * 1024,
) {
    var nextChunkBytes: Long = initialChunkBytes
        private set
    private var slowReadSamples = 0
    private var fastReadSamples = 0

    /** Time only an actual upstream read(), never time Media3 spent idle. */
    fun observeRead(requestedBytes: Int, readBytes: Int, elapsedMs: Long) {
        if (requestedBytes < minRequestedBytes || readBytes <= 0) return
        val readBps = readBytes.toDouble() * 1000.0 / elapsedMs.coerceAtLeast(1L)
        when {
            elapsedMs >= slowReadMs && readBps < slowBps -> {
                slowReadSamples = (slowReadSamples + 1).coerceAtMost(8)
                fastReadSamples = 0
            }
            readBps >= recoveredBps -> {
                fastReadSamples = (fastReadSamples + 1).coerceAtMost(8)
                if (fastReadSamples >= 2) slowReadSamples = 0
            }
        }
    }

    /** Called only at a completed range seam: never abort an in-flight CDN read. */
    fun onChunkComplete(): Long {
        nextChunkBytes = when {
            slowReadSamples >= 3 -> minOf(initialChunkBytes, reducedChunkBytes)
            nextChunkBytes < initialChunkBytes && fastReadSamples >= 3 -> initialChunkBytes
            else -> nextChunkBytes
        }.coerceAtLeast(1L)
        // A single genuinely slow read at the end of one range may accumulate
        // with the next one; a healthy pair clears stale slow evidence.
        fastReadSamples = 0
        if (slowReadSamples >= 3) slowReadSamples = 0
        return nextChunkBytes
    }
}

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
    private val initialChunkBytes: (DataSpec) -> Long = { 0L },
) : DataSource {
    private var request: DataSpec? = null
    private var nextPosition = 0L
    private var bytesLeft = 0L
    private var chunkLeft = 0L
    private var opened = false
    private var activeChunkBytes = chunkBytes
    private var firstChunkBytes = 0L
    private var firstChunkPending = false
    private var adaptiveChunkBytes = chunkBytes
    private var ratePolicy = AudioCdnChunkRatePolicy(chunkBytes)
    private var currentChunkBytes = 0L
    private val opusReadAhead = ByteArray(OPUS_READAHEAD_BYTES)
    private var bufferedOffset = 0
    private var bufferedLength = 0
    private var opusReadAheadEnabled = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        bufferedOffset = 0
        bufferedLength = 0
        opusReadAheadEnabled = isOpusChunkKey(dataSpec.key)
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
            return openWithRefusalRetry(dataSpec)
        }

        // Stage the first slice even when the entire song is smaller than the
        // extractor's normal chunk: those short songs are the common rapid-skip case.
        val firstBytes =
            initialChunkBytes(dataSpec)
                .takeIf { it > 0L && dataSpec.position == 0L }
                ?.coerceAtMost(chunkBytes)
                ?: chunkBytes
        if (!shouldChunkAudioRequest(dataSpec.length, firstBytes)) {
            request = null
            opened = true
            return openWithRefusalRetry(dataSpec)
        }

        request = dataSpec
        activeChunkBytes = chunkBytes
        adaptiveChunkBytes = chunkBytes
        ratePolicy = AudioCdnChunkRatePolicy(chunkBytes)
        firstChunkBytes = firstBytes
        firstChunkPending = true
        nextPosition = dataSpec.position
        bytesLeft = dataSpec.length
        opened = true
        openNextChunk()
        return dataSpec.length
    }

    /** Opens the next bounded slice without changing the original URL or extraction contract. */
    private fun openNextChunk() {
        val spec = requireNotNull(request)
        chunkLeft = minOf(if (firstChunkPending) firstChunkBytes else adaptiveChunkBytes, bytesLeft)
        firstChunkPending = false
        currentChunkBytes = chunkLeft
        val chunkSpec =
            spec.buildUpon()
                .setPosition(nextPosition)
                .setLength(chunkLeft)
                .build()

        openWithRefusalRetry(chunkSpec)
    }

    /**
     * A short bounded retry also applies to small and unknown-length streams. Captured 403s can
     * succeed on the unchanged request; persistent refusals and all transport failures propagate
     * to the service's shared recovery budget. Never retry 429 or a cancellation here.
     */
    private fun openWithRefusalRetry(spec: DataSpec): Long {
        var attempt = 1
        while (true) {
            try {
                return upstream.open(spec)
            } catch (refused: InvalidResponseCodeException) {
                if (refused.responseCode !in REFUSAL_CODES || attempt >= CHUNK_OPEN_ATTEMPTS) throw refused
                if (GlobalLog.isEnabled) {
                    Timber.tag("AudioCDN").w(
                        "cdn-chunk-refused linkRef=%s position=%d length=%d attempt=%d code=%d; asking again",
                        AudioCdnLinkIdentity.ref(spec.uri.toString()),
                        spec.position,
                        spec.length,
                        attempt,
                        refused.responseCode,
                    )
                }
                upstream.close()
                attempt += 1
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (request == null) return upstream.read(buffer, offset, length)
        if (bytesLeft == 0L) return C.RESULT_END_OF_INPUT

        if (chunkLeft == 0L) {
            // Every cached byte must be delivered before crossing a CDN range.
            check(bufferedOffset == bufferedLength)
            upstream.close()
            openNextChunk()
        }

        val requested = minOf(length.toLong(), chunkLeft).toInt()
        val read: Int
        if (opusReadAheadEnabled && bufferedOffset < bufferedLength) {
            // No socket access on the thousands of tiny WebM extractor reads.
            read = minOf(requested, bufferedLength - bufferedOffset)
            opusReadAhead.copyInto(buffer, offset, bufferedOffset, bufferedOffset + read)
            bufferedOffset += read
        } else if (opusReadAheadEnabled && requested < OPUS_READAHEAD_BYTES) {
            // At most the remainder of THIS bounded request. No speculative
            // requests, no seek across slices, no write of unused bytes to cache.
            val upstreamRequested = minOf(OPUS_READAHEAD_BYTES.toLong(), chunkLeft).toInt()
            val filled = upstream.read(opusReadAhead, 0, upstreamRequested)
            // Do not reinterpret a coalesced 8K read as evidence for CDN
            // rate adaptation. Before this change Media3 requested tiny
            // WebM fragments that deliberately did not drive that policy.
            // Keep the same next-range sizes and recovery budget.
            if (filled > 0) {
                bufferedOffset = minOf(requested, filled)
                bufferedLength = filled
                opusReadAhead.copyInto(buffer, offset, 0, bufferedOffset)
                read = bufferedOffset
            } else {
                read = filled
            }
        } else {
            val startedNs = System.nanoTime()
            read = upstream.read(buffer, offset, requested)
            // Measure actual upstream blocking, never total Media3 idle time.
            // Tiny reads are not evidence of CDN throttling.
            if (requested >= 8 * 1024) ratePolicy.observeRead(
                requestedBytes = requested,
                readBytes = read,
                elapsedMs = ((System.nanoTime() - startedNs).coerceAtLeast(0L) / 1_000_000L),
            )
        }
        if (read == C.RESULT_END_OF_INPUT) {
            // This is a bounded request with a known remaining length: EOF before chunkLeft
            // reaches zero means that the CDN truncated this slice, not that the song ended.
            // Never commit a partial cache entry as if it were the complete audio stream.
            // Propagate a transport error through the existing bounded fresh-URL recovery.
            throw java.io.EOFException(
                "Audio CDN ended a bounded slice early (remainingBytes=$chunkLeft)",
            )
        }

        nextPosition += read
        bytesLeft -= read
        chunkLeft -= read
        if (chunkLeft == 0L) {
            val oldChunkBytes = adaptiveChunkBytes
            adaptiveChunkBytes = ratePolicy.onChunkComplete()
            if (oldChunkBytes != adaptiveChunkBytes && GlobalLog.isEnabled) {
                Timber.tag("AudioCDN").i(
                    "cdn-range-adapt oldBytes=%d nextBytes=%d trigger=slow-read-samples",
                    oldChunkBytes,
                    adaptiveChunkBytes,
                )
            }
        }
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
        firstChunkPending = false
        adaptiveChunkBytes = chunkBytes
        currentChunkBytes = 0L
        bufferedOffset = 0
        bufferedLength = 0
        opusReadAheadEnabled = false
        upstream.close()
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkBytes: Long = AUDIO_CHUNK_BYTES,
        private val initialChunkBytes: (DataSpec) -> Long = { 0L },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioChunkedDataSource(upstreamFactory.createDataSource(), chunkBytes, initialChunkBytes)
    }
}
