package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns MusicService-level audio resolve lifecycle.
 *
 * This is deliberately separate from [AudioResolveScheduler]:
 * - AudioResolveScheduler decides which extractor request may use the transport now.
 * - AudioResolveCoordinator owns shared in-flight work, policy generations,
 *   prefetch generations and cancellation/invalidation around MusicService.
 */
internal class AudioResolveCoordinator<T>(
    private val scopeProvider: () -> CoroutineScope,
    private val cachedValue: (mediaId: String) -> T?,
) {
    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<T>>>()

    @Volatile
    private var policyGeneration: Long = 0L

    @Volatile
    private var prefetchGeneration: Long = 0L

    fun resolve(
        mediaId: String,
        block: suspend (policyGeneration: Long) -> Result<T>,
    ): Deferred<Result<T>> =
        synchronized(lock) {
            cachedValue(mediaId)?.let { cached ->
                return@synchronized CompletableDeferred(Result.success(cached))
            }

            inFlight[mediaId]
                ?.takeIf { !it.isCompleted }
                ?: run {
                    val generation = policyGeneration
                    scopeProvider()
                        .async {
                            block(generation)
                        }
                        .also { job ->
                            inFlight[mediaId] = job
                            job.invokeOnCompletion {
                                inFlight.remove(mediaId, job)
                            }
                        }
                }
        }

    fun hasInFlight(mediaId: String): Boolean =
        inFlight[mediaId]?.isCompleted == false

    fun cancelStaleExcept(relevantIds: Set<String>): List<String> {
        val cancelled = ArrayList<String>()
        synchronized(lock) {
            inFlight.forEach { (mediaId, job) ->
                if (
                    mediaId !in relevantIds &&
                    inFlight.remove(mediaId, job)
                ) {
                    job.cancel()
                    cancelled += mediaId
                }
            }
        }
        return cancelled
    }

    fun cancelMedia(
        mediaId: String,
        onInvalidate: () -> Unit = {},
    ) {
        synchronized(lock) {
            inFlight.remove(mediaId)?.cancel()
            onInvalidate()
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            cancelAllLocked()
        }
    }

    fun invalidatePolicy(
        invalidatePrefetch: Boolean,
        onInvalidate: () -> Unit = {},
    ) {
        synchronized(lock) {
            policyGeneration += 1L
            if (invalidatePrefetch) {
                prefetchGeneration += 1L
            }
            cancelAllLocked()
            onInvalidate()
        }
    }

    fun invalidatePrefetches() {
        synchronized(lock) {
            prefetchGeneration += 1L
        }
    }

    fun nextPrefetchGeneration(): Long =
        synchronized(lock) {
            prefetchGeneration += 1L
            prefetchGeneration
        }

    fun isPrefetchGenerationCurrent(generation: Long): Boolean =
        generation == prefetchGeneration

    fun isPolicyGenerationCurrent(generation: Long): Boolean =
        generation == policyGeneration

    fun publishIfCurrent(
        generation: Long,
        block: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (generation != policyGeneration) {
                false
            } else {
                block()
                true
            }
        }

    private fun cancelAllLocked() {
        inFlight.forEach { (mediaId, job) ->
            if (inFlight.remove(mediaId, job)) {
                job.cancel()
            }
        }
    }
}
