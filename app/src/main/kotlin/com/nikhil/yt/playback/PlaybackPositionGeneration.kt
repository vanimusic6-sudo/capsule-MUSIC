package com.nikhil.yt.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic marker for meaningful player position discontinuities.
 *
 * Delayed playback recovery should be invalidated by an actual seek/discontinuity,
 * not by a tiny currentPosition update that races with an error callback.
 */
internal class PlaybackPositionGeneration {
    private val generation = AtomicLong(0L)

    fun markDiscontinuity(): Long = generation.incrementAndGet()

    fun snapshot(): Long = generation.get()

    fun isCurrent(snapshot: Long): Boolean = generation.get() == snapshot
}
