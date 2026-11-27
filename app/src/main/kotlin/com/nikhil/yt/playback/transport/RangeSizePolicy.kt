package com.nikhil.yt.playback.transport

/**
 * Adaptive ranges ONLY for measured slow socket reads, never total time
 * between open() and close(). Media3 can suspend reads for a full buffer:
 * cdn-close elapsedMs and deliveredToLoaderBps are not network throughput.
 * Without actual slow read() samples, preserve InnerTubeX's chunk sizing.
 */
internal class RangeSizePolicy(
    private val initialChunkBytes: Long,
    private val reducedChunkBytes: Long = 512L * 1024,
    private val minRequestedBytes: Int = 8 * 1024,
    private val slowReadMs: Long = 250L,
    private val slowBps: Long = 48L * 1024,
    private val recoveredBps: Long = 160L * 1024,
    private val maximumChunkBytes: Long = initialChunkBytes,
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
            fastReadSamples >= 3 -> minOf(maximumChunkBytes, nextChunkBytes * 2L)
            else -> nextChunkBytes
        }.coerceAtLeast(1L)
        // A single genuinely slow read at the end of one range may accumulate
        // with the next one; a healthy pair clears stale slow evidence.
        fastReadSamples = 0
        if (slowReadSamples >= 3) slowReadSamples = 0
        return nextChunkBytes
    }
}
