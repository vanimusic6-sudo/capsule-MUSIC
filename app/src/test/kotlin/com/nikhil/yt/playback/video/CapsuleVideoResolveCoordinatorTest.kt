package com.nikhil.yt.playback.video

import com.nikhil.yt.constants.CapsuleVideoQuality
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVideoResolveCoordinatorTest {
    private fun request(id: String) =
        CapsuleVideoResolveRequest(
            sourceMediaId = id,
            title = id,
            artists = emptyList(),
            durationSeconds = null,
            quality = CapsuleVideoQuality.AUTO,
        )

    @Test
    fun cancelledResolveCannotPublish() = runTest {
        val published = mutableListOf<String>()
        val coordinator =
            CapsuleVideoResolveCoordinator(
                scopeProvider = { this },
                resolver = { request ->
                    delay(100)
                    Result.failure(IllegalStateException(request.sourceMediaId))
                },
            )

        coordinator.resolve(request("old"), isRelevant = { true }) {
            published += it.exceptionOrNull()?.message.orEmpty()
        }
        runCurrent()
        coordinator.cancel()
        advanceUntilIdle()

        assertTrue(published.isEmpty())
    }

    @Test
    fun newerGenerationWinsEvenIfOldResolverIgnoresCancellation() = runTest {
        val published = mutableListOf<String>()
        val coordinator =
            CapsuleVideoResolveCoordinator(
                scopeProvider = { this },
                resolver = { request ->
                    if (request.sourceMediaId == "old") {
                        withContext(NonCancellable) { delay(100) }
                    } else {
                        delay(10)
                    }
                    Result.failure(IllegalStateException(request.sourceMediaId))
                },
            )

        coordinator.resolve(request("old"), isRelevant = { true }) {
            published += it.exceptionOrNull()?.message.orEmpty()
        }
        runCurrent()
        coordinator.resolve(request("new"), isRelevant = { true }) {
            published += it.exceptionOrNull()?.message.orEmpty()
        }
        advanceUntilIdle()

        assertEquals(listOf("new"), published)
    }

    @Test
    fun irrelevantResultIsDropped() = runTest {
        val published = mutableListOf<String>()
        val coordinator =
            CapsuleVideoResolveCoordinator(
                scopeProvider = { this },
                resolver = { request -> Result.failure(IllegalStateException(request.sourceMediaId)) },
            )

        coordinator.resolve(request("stale"), isRelevant = { false }) {
            published += it.exceptionOrNull()?.message.orEmpty()
        }
        advanceUntilIdle()

        assertTrue(published.isEmpty())
    }
}
