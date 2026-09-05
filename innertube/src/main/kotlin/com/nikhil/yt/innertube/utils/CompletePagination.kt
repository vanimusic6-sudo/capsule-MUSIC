package com.nikhil.yt.innertube.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded fetch must never describe a truncated collection as complete. */
class IncompletePaginationException(message: String) : IllegalStateException(message)

internal suspend fun <T> collectCompletePages(
    initialItems: List<T>,
    initialContinuation: String?,
    maxRequests: Int = 50,
    loadPage: suspend (String) -> Pair<List<T>, String?>,
): List<T> {
    val items = initialItems.toMutableList()
    val seen = mutableSetOf<String>()
    var continuation = initialContinuation
    while (continuation != null) {
        currentCoroutineContext().ensureActive()
        if (seen.size >= maxRequests || !seen.add(continuation)) {
            throw IncompletePaginationException("YouTube collection is incomplete; refusing to replace local data")
        }
        val (nextItems, nextContinuation) = loadPage(continuation)
        items += nextItems
        continuation = nextContinuation
    }
    currentCoroutineContext().ensureActive()
    return items
}
