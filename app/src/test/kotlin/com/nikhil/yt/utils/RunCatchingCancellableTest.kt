package com.nikhil.yt.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancelled coroutine must not be able to catch its own cancellation.
 *
 * `runCatching` catches `Throwable`, and cancellation is thrown. A lyrics lookup for a track the
 * listener had already skipped past was catching that, logging it, returning an empty result and
 * carrying on working for a track nobody wanted — in a capture it shows up as "The coroutine
 * scope left the composition" arriving as a failure to be handled rather than as the scope
 * leaving.
 */
class RunCatchingCancellableTest {
    @Test fun `an ordinary failure is still captured`() {
        val result = runCatchingCancellable { error("provider is down") }

        assertTrue(result.isFailure)
        assertEquals("provider is down", result.exceptionOrNull()?.message)
    }

    @Test fun `a success passes through`() {
        assertEquals("lyrics", runCatchingCancellable { "lyrics" }.getOrNull())
    }

    @Test fun `cancellation is rethrown, not captured`() {
        var escaped = false
        try {
            runCatchingCancellable { throw CancellationException("track changed") }
        } catch (expected: CancellationException) {
            escaped = true
        }

        assertTrue("cancellation was swallowed", escaped)
    }

    @Test fun `a cancelled coroutine actually unwinds`() = runBlocking {
        // The behaviour that matters, rather than the exception type: the work stops.
        var ranAfterCancellation = false
        val job =
            async {
                runCatchingCancellable { delay(10_000) }
                ranAfterCancellation = true
            }
        delay(50)
        job.cancel()
        job.join()

        assertFalse("work continued past cancellation", ranAfterCancellation)
        assertTrue(job.isCancelled)
    }

    @Test fun `plain runCatching is what this exists to avoid`() = runBlocking<Unit> {
        // Documents the trap: the same code with runCatching keeps going.
        var ranAfterCancellation = false
        val job =
            async {
                @Suppress("SwallowedException")
                runCatching { delay(10_000) }
                ranAfterCancellation = true
            }
        delay(50)
        job.cancel()
        job.join()

        // runCatching catches the CancellationException, so the line after it runs.
        assertTrue("runCatching no longer swallows cancellation", ranAfterCancellation)
        assertTrue(job.isCancelled)
    }

    @Test fun `it does not need a coroutine to be useful`() {
        assertEquals(Job().let { 7 }, runCatchingCancellable { 7 }.getOrThrow())
    }
}
