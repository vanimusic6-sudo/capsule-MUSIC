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

/**
 * VIDEO-only health, remembered within the isolated extractor process.
 *
 * A previous bot verdict is an explicit signal to stop probing that route for
 * a while, not merely to place it at the end of a five-client carousel.
 * Remember the last working backend so a subsequent clip does not hit two
 * already-rejected clients before trying a route that just succeeded.
 */
internal class CapsuleVideoRouteHealth(private val clock: () -> Long = System::nanoTime) {
    private val retryAfter = mutableMapOf<CapsuleVideoRoute, Long>()
    private val botQuietUntil = mutableMapOf<CapsuleVideoRoute, Long>()
    private var lastSuccessful: CapsuleVideoRoute? = null

    @Synchronized fun order(failedBackend: String?): List<CapsuleVideoRoute> {
        val now = clock()
        return CapsuleVideoRoute.entries.sortedWith(compareBy<CapsuleVideoRoute>
            { it.wireName == failedBackend }
            .thenBy { (retryAfter[it] ?: 0L) > now }
            .thenBy { it != lastSuccessful }
            // Cold start: try the independent PipePipe parser first; once a
            // route succeeds, the measured last winner takes priority.
            .thenBy { it != CapsuleVideoRoute.PIPEPIPE })
    }

    /** Known bot-blocked clients stay quiet. An independent unblocked route
     * remains eligible; we don't identify a whole platform outage from one client. */
    @Synchronized fun candidates(failedBackend: String?): List<CapsuleVideoRoute> {
        val now = clock()
        return order(failedBackend).filter { (botQuietUntil[it] ?: 0L) <= now }
    }

    @Synchronized fun pausedBotCount(): Int {
        val now = clock()
        return CapsuleVideoRoute.entries.count { (botQuietUntil[it] ?: 0L) > now }
    }

    @Synchronized fun success(route: CapsuleVideoRoute) {
        retryAfter.remove(route)
        botQuietUntil.remove(route)
        lastSuccessful = route
    }

    @Synchronized
    fun failure(route: CapsuleVideoRoute, kind: CapsuleNewPipeFailure? = null) {
        val now = clock()
        retryAfter[route] = now + TimeUnit.SECONDS.toNanos(45)
        if (kind == CapsuleNewPipeFailure.BOT_BLOCKED) {
            botQuietUntil[route] = now + TimeUnit.MINUTES.toNanos(10)
        }
        if (lastSuccessful == route) lastSuccessful = null
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
    val routes = health?.candidates(failedBackend)
        ?: CapsuleVideoRoute.entries.sortedBy { it.wireName == failedBackend }
    val pausedBotRoutes = health?.pausedBotCount() ?: 0
    // If every route already explicitly reported an anti-bot response in this
    // process, do not send another five /player calls just to get the same answer.
    if (routes.isEmpty() && pausedBotRoutes > 0) {
        throw CapsuleVideoRoutesException(CapsuleNewPipeFailure.BOT_BLOCKED, null)
    }
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
            val kind = CapsuleNewPipeExtractor.classify(failure)
            if ((failure as? CapsuleDirectVideoException)?.penalizeRoute != false)
                health?.failure(route, kind)
            onAttempt(route, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), false)
            failures += failure
        }
    }
    // A client-specific bot response is not proof that every independent
    // route is blocked. Never turn a mixture of parser/network/no-format
    // failures into a global ten-minute ban based on just one backend.
    val allBotBlocked = (failures.size + pausedBotRoutes == CapsuleVideoRoute.entries.size) &&
        (failures.isNotEmpty() || pausedBotRoutes > 0) &&
        failures.all { CapsuleNewPipeExtractor.classify(it) == CapsuleNewPipeFailure.BOT_BLOCKED }
    val kind = when {
        allBotBlocked -> CapsuleNewPipeFailure.BOT_BLOCKED
        failures.any { CapsuleNewPipeExtractor.classify(it) == CapsuleNewPipeFailure.NETWORK } -> CapsuleNewPipeFailure.NETWORK
        else -> CapsuleNewPipeFailure.EXTRACTOR
    }
    throw CapsuleVideoRoutesException(kind, failures.lastOrNull()).also { combined ->
        failures.forEach(combined::addSuppressed)
    }
}
