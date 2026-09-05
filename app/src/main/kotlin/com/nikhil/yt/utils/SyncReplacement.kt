package com.nikhil.yt.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Call inside the database transaction. Cancellation must roll back, never return normally. */
internal suspend fun <T> replaceSyncedItems(
    items: List<T>,
    isSyncActive: () -> Boolean,
    clear: () -> Unit,
    insert: suspend (Int, T) -> Unit,
) {
    suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (!isSyncActive()) throw CancellationException("YouTube synchronization disabled")
    }
    checkActive()
    clear()
    items.forEachIndexed { index, item ->
        checkActive()
        insert(index, item)
    }
    checkActive()
}
