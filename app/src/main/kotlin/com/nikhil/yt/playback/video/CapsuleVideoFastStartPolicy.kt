/* Capsule MUSIC — fast VIDEO route admission, GPL-3.0. */
package com.nikhil.yt.playback.video

import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock

/** The primary WEB path gets a short foreground VIDEO-only budget.
 * If it stalls, the already-isolated parser still gets a proper chance.
 * A 5.2s WEB timeout + 4.8s PipePipe timeout + decoder startup alone used
 * to exceed the desired first-frame window before trying VisionOS.
 * These are per-route ceilings, not a hard playback deadline.
 * AUDIO, bot checks, signed URL validation and per-route cancellation stay intact.
 */
internal object CapsuleVideoFastStartPolicy {
    fun primaryBudgetMs(maxHeight: Int?): Long = when {
        maxHeight == null || maxHeight <= 480 -> 3_200L
        maxHeight <= 720 -> 3_800L
        maxHeight <= 1080 -> 4_600L
        else -> 5_200L
    }
}

/**
 * Detect a consistently stalled primary route across DISTINCT clips. A bad
 * individual video must never blacklist WEB_REMIX. After two independent
 * deadlines, prefer the isolated fallback for 30 seconds; then probe WEB
 * again. Session changes immediately discard the learned health.
 *
 * No extra requests, timers or periodic jobs. Cooldown is checked only when
 * the user explicitly asks for VIDEO; it does not affect streaming AUDIO.
 */
internal class CapsuleVideoPrimaryRouteHealth(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val cooldownMs: Long = 30_000L,
) {
    init { require(cooldownMs > 0L) }
    private var session: Any? = null
    private var lastTimedOutVideo: String? = null
    private var consecutiveDistinctDeadlines = 0
    private var bypassUntilMs = 0L

    @Synchronized fun shouldTryWeb(sessionIdentity: Any): Boolean {
        if (session != sessionIdentity) {
            session = sessionIdentity
            lastTimedOutVideo = null
            consecutiveDistinctDeadlines = 0
            bypassUntilMs = 0L
        }
        return nowMs() >= bypassUntilMs
    }

    /** A prompt result (including no formats) is not a transport timeout. */
    @Synchronized fun onWebCompleted() {
        lastTimedOutVideo = null
        consecutiveDistinctDeadlines = 0
        bypassUntilMs = 0L
    }

    @Synchronized fun onWebDeadline(videoId: String) {
        if (videoId.isBlank() || nowMs() < bypassUntilMs ||
            lastTimedOutVideo == videoId) return
        lastTimedOutVideo = videoId
        consecutiveDistinctDeadlines++
        if (consecutiveDistinctDeadlines >= 2) {
            bypassUntilMs = nowMs() + cooldownMs
            consecutiveDistinctDeadlines = 0
            lastTimedOutVideo = null
        }
    }
}

/** A local route timeout falls back; parent cancellation or an explicit
 * remote error still propagates without any additional request.
 */
internal suspend fun <T : Any> capsuleFastPrimaryOrFallback(
    primaryBudgetMs: Long,
    primary: suspend () -> T?,
    onPrimaryDeadline: () -> Unit = {},
    fallback: suspend () -> T,
): T {
    require(primaryBudgetMs in 1..30_000L)
    var primaryFinished = false
    val resolved = withTimeoutOrNull(primaryBudgetMs) {
        primary().also { primaryFinished = true }
    }
    if (!primaryFinished) onPrimaryDeadline()
    return resolved ?: fallback()
}
