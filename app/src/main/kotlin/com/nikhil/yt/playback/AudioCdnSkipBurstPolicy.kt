package com.nikhil.yt.playback

/**
 * Limits wasted CDN work only after several consecutive READY -> next transitions.
 *
 * An ordinary next button press does not trigger it. The current item still
 * starts immediately apart from a bounded short open-spacing delay during a
 * confirmed burst. No client identity, visitor data or signed URL is modified.
 *
 * All state is process-local and all operations are synchronized: Media3 opens
 * streams on a loader thread while its callbacks arrive on the player looper.
 */
internal class AudioCdnSkipBurstPolicy(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var currentId: String? = null
    private var readyId: String? = null
    private var readyAtMs = 0L
    private var quickSwitches = 0
    private var lastQuickSwitchMs = 0L

    @Synchronized
    fun onTransition(newId: String?) {
        if (newId == currentId) return
        val now = clockMs()
        val fromRecentlyReady =
            currentId != null && currentId == readyId &&
                now - readyAtMs in 0L..QUICK_SKIP_WINDOW_MS
        if (fromRecentlyReady) {
            quickSwitches =
                if (now - lastQuickSwitchMs in 0L..BURST_EXPIRY_MS) quickSwitches + 1 else 1
            lastQuickSwitchMs = now
        } else if (now - lastQuickSwitchMs > BURST_EXPIRY_MS ||
            (currentId == readyId && now - readyAtMs > HEALTHY_LISTEN_MS)
        ) {
            quickSwitches = 0
        }
        currentId = newId
        readyId = null
        readyAtMs = 0L
    }

    @Synchronized
    fun onReady(id: String?, playWhenReady: Boolean) {
        if (!playWhenReady || id == null || id != currentId || readyId == id) return
        readyId = id
        readyAtMs = clockMs()
    }

    @Synchronized
    fun isBurst(id: String?): Boolean =
        id != null && id == currentId && quickSwitches >= BURST_START &&
            clockMs() - lastQuickSwitchMs in 0L..BURST_EXPIRY_MS

    @Synchronized
    fun firstOpenDelayMs(id: String?, position: Long): Long =
        if (position == 0L && isBurst(id)) {
            (FIRST_OPEN_DELAY_MS + (quickSwitches - BURST_START) * DELAY_STEP_MS)
                .coerceAtMost(MAX_FIRST_OPEN_DELAY_MS)
        } else {
            0L
        }

    /**
     * A staged first slice supplies enough audio to start the player without
     * automatically pulling another entire megabyte for each barely-heard song.
     * The size applies ONLY to a first slice in an active skip burst.
     */
    @Synchronized
    fun stagedFirstChunkBytes(id: String?, position: Long): Long =
        if (position == 0L && isBurst(id)) FIRST_CHUNK_BYTES else 0L

    private companion object {
        const val QUICK_SKIP_WINDOW_MS = 2_000L
        const val HEALTHY_LISTEN_MS = 7_000L
        const val BURST_EXPIRY_MS = 20_000L
        const val BURST_START = 3
        const val FIRST_OPEN_DELAY_MS = 300L
        const val DELAY_STEP_MS = 150L
        const val MAX_FIRST_OPEN_DELAY_MS = 900L
        const val FIRST_CHUNK_BYTES = 256L * 1024L
    }
}
