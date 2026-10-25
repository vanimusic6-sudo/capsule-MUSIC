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

internal class CapsuleVideoRoutesException(
    val failure: CapsuleNewPipeFailure, cause: Throwable?,
) : IOException("VIDEO routes exhausted ($failure)", cause)

/** One attempt per backend; move the failed playback backend to the end. */
internal fun resolveVideoRoutes(
    failedBackend: String? = null,
    health: CapsuleVideoRouteHealth? = null,
    onAttempt: (CapsuleVideoRoute, Long, Boolean) -> Unit = { _, _, _ -> },
    onFailure: (CapsuleVideoRoute, Throwable) -> Unit = { _, _ -> },
    resolve: (CapsuleVideoRoute) -> CapsuleNewPipeResolved?,
): CapsuleNewPipeResolved {
    val failures = mutableListOf<Exception>()
    // A URL can resolve successfully yet fail at the CDN/decoder. Remember
    // this feedback across the next clips, not just this one recovery call.
    CapsuleVideoRoute.entries.firstOrNull { it.wireName == failedBackend }?.let { health?.failure(it) }
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
        } catch (problem: Throwable) {
            // A parser compiled against an incompatible Android API is a
            // broken route. VM failures are not safe to recover here.
            val failure = when (problem) {
                is Exception -> problem
                is LinkageError -> IOException("VIDEO parser incompatible", problem)
                else -> throw problem
            }
            // Route deadlines leave room for the next backend. Request-wide
            // cancellation/deadline and rate limits stop all work immediately.
            VideoExtractionRequests.checkActive()
            onFailure(route, failure)
            if (CapsuleNewPipeExtractor.classify(failure) == CapsuleNewPipeFailure.RATE_LIMITED) throw failure
            if ((failure as? CapsuleDirectVideoException)?.penalizeRoute != false) health?.failure(route)
            onAttempt(route, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), false)
            failures += failure
        }
    }
    // A client-specific bot response is not proof that every independent
    // route is blocked. Never turn a mixture of parser/network/no-format
    // failures into a global ten-minute ban based on just one backend.
    val allBotBlocked = failures.size == routes.size && failures.all {
        CapsuleNewPipeExtractor.classify(it) == CapsuleNewPipeFailure.BOT_BLOCKED
    }
    val kind = when {
        allBotBlocked -> CapsuleNewPipeFailure.BOT_BLOCKED
        failures.any { CapsuleNewPipeExtractor.classify(it) == CapsuleNewPipeFailure.NETWORK } -> CapsuleNewPipeFailure.NETWORK
        else -> CapsuleNewPipeFailure.EXTRACTOR
    }
    throw CapsuleVideoRoutesException(kind, failures.lastOrNull()).also { combined ->
        failures.forEach(combined::addSuppressed)
    }
}
