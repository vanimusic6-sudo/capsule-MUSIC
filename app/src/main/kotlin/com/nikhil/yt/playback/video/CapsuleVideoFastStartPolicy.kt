/* Capsule MUSIC — fast VIDEO route admission, GPL-3.0. */
package com.nikhil.yt.playback.video

import kotlinx.coroutines.withTimeoutOrNull

/** The primary WEB path must not block an independent extractor for 10 seconds.
 * These budgets are ONLY the first route; never shorten normal audio streams,
 * nor bypass an explicit YouTube cooldown / bot-check response.
 */
internal object CapsuleVideoFastStartPolicy {
    private const val SLOW_ROUTE_COOLDOWN_MS = 5 * 60_000L
    private var slowWebUntilMs = 0L

    /**
     * A previously timed-out Web route must not consume 5.2 seconds again
     * before trying the already healthy isolated extractor. Healthy Web
     * attempts keep their full existing budget; this starts NO parallel
     * network probe and retains all original client/request guards.
     */
    @Synchronized
    fun primaryBudgetMs(maxHeight: Int?, nowMs: Long = System.nanoTime() / 1_000_000L): Long {
        val ordinary = when {
            maxHeight == null || maxHeight <= 480 -> 4_200L
            maxHeight <= 720 -> 5_200L
            maxHeight <= 1080 -> 6_200L
            else -> 6_800L
        }
        return if (nowMs < slowWebUntilMs) minOf(ordinary, 2_800L) else ordinary
    }

    @Synchronized
    fun notePrimaryResult(
        success: Boolean,
        elapsedMs: Long,
        allowedMs: Long,
        nowMs: Long = System.nanoTime() / 1_000_000L,
    ) {
        if (success) {
            slowWebUntilMs = 0L
        } else if (elapsedMs >= allowedMs * 3 / 4) {
            slowWebUntilMs = nowMs + SLOW_ROUTE_COOLDOWN_MS
        }
    }

    @Synchronized
    internal fun clearForTest() { slowWebUntilMs = 0L }
}

/** A local route timeout falls back; parent cancellation or an explicit
 * remote error still propagates without any additional request.
 */
internal suspend fun <T : Any> capsuleFastPrimaryOrFallback(
    primaryBudgetMs: Long,
    primary: suspend () -> T?,
    fallback: suspend () -> T,
): T {
    require(primaryBudgetMs in 1..30_000L)
    return withTimeoutOrNull(primaryBudgetMs) { primary() } ?: fallback()
}
