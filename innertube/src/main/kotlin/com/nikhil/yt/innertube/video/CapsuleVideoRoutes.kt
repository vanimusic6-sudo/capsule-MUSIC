/* Capsule MUSIC — bounded, credential-free VIDEO routes. GPL-3.0. */
package com.nikhil.yt.innertube.video

import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class CapsuleVideoRoute(val wireName: String, val budgetMs: Long) {
    ANDROID_VR("direct-android-vr", 4_000L),
    VISIONOS("direct-visionos", 4_000L),
    PIPEPIPE("pipepipe", 10_000L),
    NEWPIPE("newpipe", 10_000L),
    ANDROID_REEL("direct-android-reel", 4_000L),
}

/** A healthy route gets first turn; failed routes remain bounded fallbacks. */
internal class CapsuleVideoRouteHealth(private val clock: () -> Long = System::nanoTime) {
    private val retryAfter = mutableMapOf<CapsuleVideoRoute, Long>()
    @Synchronized fun order(failedBackend: String?): List<CapsuleVideoRoute> {
        val now = clock()
        return CapsuleVideoRoute.entries.sortedWith(compareBy<CapsuleVideoRoute>
            { it.wireName == failedBackend }
            .thenBy { (retryAfter[it] ?: 0L) > now })
    }
    @Synchronized fun success(route: CapsuleVideoRoute) { retryAfter.remove(route) }
    @Synchronized fun failure(route: CapsuleVideoRoute) {
        retryAfter[route] = clock() + TimeUnit.SECONDS.toNanos(45)
    }
}

/** One attempt per backend; move the failed playback backend to the end. */
internal fun resolveVideoRoutes(
    failedBackend: String? = null,
    health: CapsuleVideoRouteHealth? = null,
    onAttempt: (CapsuleVideoRoute, Long, Boolean) -> Unit = { _, _, _ -> },
    resolve: (CapsuleVideoRoute) -> CapsuleNewPipeResolved?,
): CapsuleNewPipeResolved {
    val failures = mutableListOf<Exception>()
    val routes = health?.order(failedBackend) ?: CapsuleVideoRoute.entries.sortedBy { it.wireName == failedBackend }
    for (route in routes) {
        VideoExtractionRequests.checkActive()
        val started = System.nanoTime()
        try {
            VideoExtractionRequests.withRouteBudget(route.budgetMs) { resolve(route) }?.let {
                health?.success(route)
                onAttempt(route, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), true)
                return it.copy(backend = route.wireName)
            }
            // No compatible format is a verdict about this clip, not an
            // endpoint outage. Do not penalize the fast route on other clips.
            onAttempt(route, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), false)
        } catch (failure: Exception) {
            // Route deadlines leave room for the next backend. Request-wide
            // cancellation/deadline and rate limits stop all work immediately.
            VideoExtractionRequests.checkActive()
            if (CapsuleNewPipeExtractor.classify(failure) == CapsuleNewPipeFailure.RATE_LIMITED) throw failure
            health?.failure(route)
            onAttempt(route, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), false)
            failures += failure
        }
    }
    // Preserve a real bot verdict for VIDEO cooldown even if a later route
    // failed differently. A fallback success never reports the earlier error.
    val failure = failures.firstOrNull {
        CapsuleNewPipeExtractor.classify(it) == CapsuleNewPipeFailure.BOT_BLOCKED
    } ?: failures.lastOrNull() ?: IOException("No compatible VIDEO stream from available routes")
    failures.filter { it !== failure }.forEach(failure::addSuppressed)
    throw failure
}
