package com.nikhil.yt.playback.audio

import kotlin.random.Random

/** Bounded jitter for request load smoothing; avoids synchronized background requests. */
internal class DownloadRequestPacing(private val random: Random = Random.Default) {
    fun nextSpacingMs(): Long = random.nextLong(MIN_SPACING_MS, MAX_SPACING_MS + 1)

    companion object {
        const val MIN_SPACING_MS = 3_200L
        const val MAX_SPACING_MS = 5_800L
    }
}
