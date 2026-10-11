/* Capsule MUSIC — bounded, credential-free VIDEO routes. GPL-3.0. */
package com.nikhil.yt.innertube.video

import java.io.IOException

internal enum class CapsuleVideoRoute(val wireName: String, val budgetMs: Long) {
    VISIONOS("direct-visionos", 4_000L),
    ANDROID_REEL("direct-android-reel", 4_000L),
    NEWPIPE("newpipe", 10_000L),
}

/** One attempt per backend; move the failed playback backend to the end. */
internal fun resolveVideoRoutes(
    failedBackend: String? = null,
    resolve: (CapsuleVideoRoute) -> CapsuleNewPipeResolved?,
): CapsuleNewPipeResolved {
    val failures = mutableListOf<Exception>()
    val routes = CapsuleVideoRoute.entries.sortedBy { it.wireName == failedBackend }
    for (route in routes) {
        VideoExtractionRequests.checkActive()
        try {
            VideoExtractionRequests.withRouteBudget(route.budgetMs) { resolve(route) }?.let {
                return it.copy(backend = route.wireName)
            }
        } catch (failure: Exception) {
            // Route deadlines leave room for the next backend. Request-wide
            // cancellation/deadline and rate limits stop all work immediately.
            VideoExtractionRequests.checkActive()
            if (CapsuleNewPipeExtractor.classify(failure) == CapsuleNewPipeFailure.RATE_LIMITED) throw failure
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
