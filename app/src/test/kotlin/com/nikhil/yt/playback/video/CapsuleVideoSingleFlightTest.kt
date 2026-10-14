package com.nikhil.yt.playback.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoSingleFlightTest {
    @Test fun liveRequestsShareOneExtraction() = runTest {
        val flight = CapsuleVideoSingleFlight<String>()
        val ready = CompletableDeferred<String>()
        var calls = 0
        val first = async { flight.run("song") { calls++; ready.await() } }
        val second = async { flight.run("song") { calls++; "duplicate" } }
        runCurrent()
        ready.complete("video")
        assertEquals("video", first.await())
        assertEquals("video", second.await())
        assertEquals(1, calls)
    }

    @Test fun fastReopenDoesNotJoinCancelledWorkStillCleaningUp() = runTest {
        val flight = CapsuleVideoSingleFlight<String>()
        val cleanup = CompletableDeferred<Unit>()
        val old = async {
            flight.run("song") {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        runCurrent()
        old.cancel()
        val current = async { flight.run("song") { "fresh video" } }
        runCurrent()
        assertTrue(current.isCompleted)
        assertEquals("fresh video", current.await())
        cleanup.complete(Unit)
        old.join()
    }

    @Test fun waitingCallerSurvivesCancellationOfTheOldOwner() = runTest {
        val flight = CapsuleVideoSingleFlight<String>()
        val old = async { flight.run("song") { awaitCancellation() } }
        val current = async { flight.run("song") { "new video" } }
        runCurrent()
        old.cancel()
        assertEquals("new video", current.await())
    }

    @Test fun cancellingAWaiterDoesNotCancelTheOwner() = runTest {
        val flight = CapsuleVideoSingleFlight<String>()
        val ready = CompletableDeferred<String>()
        val owner = async { flight.run("song") { ready.await() } }
        val waiter = async { flight.run("song") { error("duplicate") } }
        runCurrent()
        waiter.cancel()
        ready.complete("video")
        assertEquals("video", owner.await())
    }
}
