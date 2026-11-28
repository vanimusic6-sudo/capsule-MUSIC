@file:Suppress("UnsafeOptInUsageError")

package com.nikhil.yt.playback.audio

import com.nikhil.yt.playback.transport.ProgressiveRangeSource
import com.nikhil.yt.playback.transport.RangeSizePolicy
import com.nikhil.yt.playback.transport.RangePlan
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Default bounded audio request size when the extractor supplies no limit. */
internal const val AUDIO_CHUNK_BYTES = 1L * 1024 * 1024

// Field captures show reset after about a minute of connection lifetime;
// the latest range sat idle for 62 seconds before the following GET failed.
// Retire idle pooled connections after accumulated loader inactivity, not
// merely a slow transfer. No periodic keepalive traffic or extra GET.
internal const val AUDIO_CDN_RANGE_IDLE_RETIRE_MS = 45_000L

private const val LONG_FORM_AUDIO_MIN_BYTES = 12L * 1024 * 1024
private const val LONG_FORM_MAX_CHUNK_BYTES = 1L * 1024 * 1024

/** Keep short songs on the library's chosen range size. Only a large stream may
 * grow past that size, and only after the rate policy observes fast socket reads. */
internal fun adaptiveAudioMaxChunkBytes(contentLengthBytes: Long, initialBytes: Long): Long =
    if (contentLengthBytes >= LONG_FORM_AUDIO_MIN_BYTES)
        maxOf(initialBytes, LONG_FORM_MAX_CHUNK_BYTES)
    else initialBytes

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

/** Audio playback and offline transfers use the same representation-aware reader. */
internal class AudioChunkedDataSource(
    upstream: DataSource,
    chunkBytes: Long = AUDIO_CHUNK_BYTES,
    initialChunkBytes: (DataSpec) -> Long = { 0L },
    currentNetworkIdentity: () -> Long? = { null },
    retryTransientOpen: Boolean = false,
    retireIdleConnections: (() -> Unit)? = null,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ProgressiveRangeSource(
    upstream = upstream,
    planFor = { spec ->
        val size = (spec.customData as? AudioCdnOpenContext)?.rangeChunkSizeBytes
            ?.takeIf { it > 0 } ?: chunkBytes
        val first = initialChunkBytes(spec).takeIf { it > 0 && spec.position == 0L }?.coerceAtMost(size) ?: size
        if (shouldChunkAudioRequest(spec.length, first)) {
            RangePlan(first, RangeSizePolicy(size, maximumChunkBytes = adaptiveAudioMaxChunkBytes(spec.length, size)))
        } else null
    },
    networkIdentity = currentNetworkIdentity,
    readAheadFor = { if (isOpusChunkKey(it.key)) OPUS_READAHEAD_BYTES else 0 },
    canResume = { failure, _ ->
        !failure.isExpectedAudioCdnInterruption() && failure.isTransientAudioCdnSocketReset()
    },
    afterRangeClose = retireIdleConnections?.let { retire ->
        { idleMs -> if (idleMs >= AUDIO_CDN_RANGE_IDLE_RETIRE_MS) retire() }
    },
    nowMs = nowMs,
    canRetryRangeOpen = { failure ->
        retryTransientOpen && failure.isAudioCdnTransportFailure() &&
            failure.isTransientAudioCdnSocketReset() &&
            failure.audioAccessStopOrNull() == null &&
            (failure.audioCdnRefreshRequiredOrNull()?.refreshReason.let {
                it == null || it == AudioCdnRefreshReason.OPEN_FAILURE
            })
    },
) {
    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkBytes: Long = AUDIO_CHUNK_BYTES,
        private val initialChunkBytes: (DataSpec) -> Long = { 0L },
        private val currentNetworkIdentity: () -> Long? = { null },
        private val retryTransientOpen: Boolean = false,
        private val retireIdleConnections: (() -> Unit)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioChunkedDataSource(
                upstreamFactory.createDataSource(),
                chunkBytes,
                initialChunkBytes,
                currentNetworkIdentity,
                retryTransientOpen,
                retireIdleConnections,
            )
    }
}
