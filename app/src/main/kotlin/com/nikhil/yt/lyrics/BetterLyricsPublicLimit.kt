/* Capsule MUSIC — bounded rate-limit memory for public Better Lyrics endpoints. GPL-3.0. */
package com.nikhil.yt.lyrics

/** QQ and KuGou share a public API quota; do not multiply 429s across songs. */
internal object BetterLyricsPublicLimit {
    @Volatile private var blockedUntilMs = 0L
    private fun nowMs() = System.nanoTime() / 1_000_000L

    fun mayRequest(): Boolean = nowMs() >= blockedUntilMs

    @Synchronized
    fun noteResponse(status: Int, retryAfter: String?) {
        if (status != 429 && status != 503) return
        val seconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1L, 120L)
            ?: if (status == 429) 60L else 25L
        blockedUntilMs = maxOf(blockedUntilMs, nowMs() + seconds * 1_000L)
    }
}
