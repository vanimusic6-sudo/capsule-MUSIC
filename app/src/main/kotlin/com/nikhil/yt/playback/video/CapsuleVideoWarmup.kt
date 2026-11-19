/*
 * Capsule MUSIC — bounded first bytes of an already-resolved VIDEO stream.
 * This is intentionally NOT a second player or another extractor. AUDIO stays
 * audible while the VIDEO cache warms, and VIDEO and AUDIO caches never mix.
 * GPL-3.0
 */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.withContext

internal const val CAPSULE_VIDEO_WARMUP_BYTES = 256 * 1024L
internal const val CAPSULE_VIDEO_AUDIO_WARMUP_BYTES = 64 * 1024L
private const val MAX_VIDEO_WARMUP_EMPTY_READS = 12

internal suspend fun warmupVideoPrefix(
    factory: DataSource.Factory,
    url: String,
    cacheKey: String,
    bytes: Long,
): Long = withContext(Dispatchers.IO) {
    require(bytes in 1..(512L * 1024L)) { "VIDEO warmup must stay bounded" }
    val dataSource = factory.createDataSource()
    val dataSpec = DataSpec.Builder()
        .setUri(url)
        .setKey(cacheKey)
        .setPosition(0)
        .setLength(bytes)
        .build()
    var delivered = 0L
    var emptyReads = 0
    try {
        dataSource.open(dataSpec)
        val buffer = ByteArray(16 * 1024)
        while (delivered < bytes) {
            currentCoroutineContext().ensureActive()
            val maxRead = minOf(buffer.size.toLong(), bytes - delivered).toInt()
            val read = dataSource.read(buffer, 0, maxRead)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read == 0) {
                // A broken/nonblocking DataSource must not hot-loop forever
                // while AUDIO is playing, wasting CPU and battery. Real HTTP
                // DataSources normally block and return actual bytes.
                if (++emptyReads >= MAX_VIDEO_WARMUP_EMPTY_READS) {
                    throw IOException("VIDEO prefix warmup made no byte progress")
                }
                delay(20L)
                continue
            }
            emptyReads = 0
            delivered += read
        }
        check(delivered > 0L) { "VIDEO CDN returned zero playable bytes" }
        delivered
    } finally {
        // A short speculative warmup already delivered its cached prefix.
        // A CDN/socket close exception must not turn those valid bytes into
        // a VIDEO startup failure and trigger another expensive extraction.
        // The optional DataSource is never reused by the active decoder.
        try {
            dataSource.close()
        } catch (_: IOException) {
            // Closing was attempted; regular playback owns stream failures.
        }
    }
}
