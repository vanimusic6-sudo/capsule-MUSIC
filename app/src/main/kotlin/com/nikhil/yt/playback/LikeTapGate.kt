package com.nikhil.yt.playback

/** Ignore accidental repeated taps while retaining immediate feedback and per-track intent. */
internal class LikeTapGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val intervalMs: Long = 500L,
) {
    private var lastMediaId: String? = null
    private var acceptedAtMs = 0L

    @Synchronized
    fun accept(mediaId: String): Boolean {
        val now = nowMs()
        if (lastMediaId == mediaId && now - acceptedAtMs in 0 until intervalMs) return false
        lastMediaId = mediaId
        acceptedAtMs = now
        return true
    }
}
