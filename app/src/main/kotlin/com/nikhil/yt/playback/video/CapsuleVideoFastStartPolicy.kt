/* Capsule MUSIC — fast VIDEO route admission, GPL-3.0. */
package com.nikhil.yt.playback.video

import kotlinx.coroutines.withTimeoutOrNull

/** The primary WEB path must not block an independent extractor for 10 seconds.
 * These budgets are ONLY the first route; never shorten normal audio streams,
 * nor bypass an explicit YouTube cooldown / bot-check response.
 */
internal object CapsuleVideoFastStartPolicy {
    fun primaryBudgetMs(maxHeight: Int?): Long = when {
        maxHeight == null || maxHeight <= 480 -> 4_200L
        maxHeight <= 720 -> 5_200L
        maxHeight <= 1080 -> 6_200L
        else -> 6_800L
    }
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
