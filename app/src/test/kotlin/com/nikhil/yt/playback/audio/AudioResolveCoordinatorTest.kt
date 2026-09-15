package com.nikhil.yt.playback.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioResolveCoordinatorTest {
    @Test
    fun sameMediaIdSharesOneInFlightResolve() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        val first =
            coordinator.resolve("track") {
                calls += 1
                gate.await()
                Result.success(7)
            }
        val second =
            coordinator.resolve("track") {
                calls += 1
                Result.success(99)
            }

        assertSame(first, second)
        runCurrent()
        assertEquals(1, calls)

        gate.complete(Unit)

        assertEquals(7, first.await().getOrThrow())
        assertEquals(7, second.await().getOrThrow())
    }

    @Test
    fun cachedValueBypassesInFlightWork() = runTest {
        var calls = 0
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { mediaId -> if (mediaId == "cached") 42 else null },
            )

        val result =
            coordinator.resolve("cached") {
                calls += 1
                Result.success(1)
            }

        assertEquals(42, result.await().getOrThrow())
        assertEquals(0, calls)
        assertFalse(coordinator.hasInFlight("cached"))
    }

    @Test
    fun stalePrefetchesAreCancelledWithoutTouchingRelevantJobs() = runTest {
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        val current = coordinator.resolve("current") { awaitCancellation() }
        val next = coordinator.resolve("next") { awaitCancellation() }
        val stale = coordinator.resolve("stale") { awaitCancellation() }
        runCurrent()

        val cancelled = coordinator.cancelStaleExcept(setOf("current", "next"))
        runCurrent()

        assertEquals(listOf("stale"), cancelled)
        assertTrue(stale.isCancelled)
        assertFalse(current.isCancelled)
        assertFalse(next.isCancelled)

        coordinator.cancelAll()
    }

    @Test
    fun policyInvalidationCancelsWorkRejectsOldPublicationAndRunsInvalidation() = runTest {
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        var capturedGeneration = -1L
        var cacheCleared = false
        var published = false

        val job =
            coordinator.resolve("track") { generation ->
                capturedGeneration = generation
                awaitCancellation()
            }
        runCurrent()

        assertTrue(coordinator.isPolicyGenerationCurrent(capturedGeneration))

        coordinator.invalidatePolicy(
            invalidatePrefetch = true,
            onInvalidate = { cacheCleared = true },
        )
        runCurrent()

        assertTrue(job.isCancelled)
        assertTrue(cacheCleared)
        assertFalse(coordinator.isPolicyGenerationCurrent(capturedGeneration))
        assertFalse(
            coordinator.publishIfCurrent(capturedGeneration) {
                published = true
            },
        )
        assertFalse(published)
    }

    @Test
    fun cancelMediaOnlyCancelsRequestedTrackAndKeepsInvalidationAtomic() = runTest {
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        val a = coordinator.resolve("a") { awaitCancellation() }
        val b = coordinator.resolve("b") { awaitCancellation() }
        runCurrent()

        var invalidated = false
        coordinator.cancelMedia("a") {
            invalidated = true
        }
        runCurrent()

        assertTrue(a.isCancelled)
        assertFalse(b.isCancelled)
        assertTrue(invalidated)
        assertFalse(coordinator.hasInFlight("a"))
        assertTrue(coordinator.hasInFlight("b"))

        coordinator.cancelAll()
    }

    @Test
    fun prefetchGenerationCanBeInvalidatedIndependently() = runTest {
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        val first = coordinator.nextPrefetchGeneration()
        assertTrue(coordinator.isPrefetchGenerationCurrent(first))

        coordinator.invalidatePrefetches()

        assertFalse(coordinator.isPrefetchGenerationCurrent(first))
        val second = coordinator.nextPrefetchGeneration()
        assertTrue(coordinator.isPrefetchGenerationCurrent(second))
    }

    @Test
    fun completedResolveIsRemovedAndNextResolveCanOwnTheMediaId() = runTest {
        var calls = 0
        val coordinator =
            AudioResolveCoordinator<Int>(
                scopeProvider = { this },
                cachedValue = { null },
            )

        val first =
            coordinator.resolve("track") {
                calls += 1
                Result.success(calls)
            }
        assertEquals(1, first.await().getOrThrow())
        runCurrent()
        assertFalse(coordinator.hasInFlight("track"))

        val second =
            coordinator.resolve("track") {
                calls += 1
                Result.success(calls)
            }

        assertEquals(2, second.await().getOrThrow())
        assertEquals(2, calls)
    }
}
