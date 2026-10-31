package com.nikhil.yt.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AudioDownloadSlotsTest {
    @Test fun bothPipelinesShareTwoSlotsAndCancelledWaitersCannotConsumeTheNextSlot() = runBlocking {
        val gates = List(4) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        var active = 0
        var max = 0
        val jobs = List(4) { index -> launch {
            AudioDownloadSlots.withSlot {
                active++; max = maxOf(max, active); started.add(index)
                try { gates[index].await() } finally { active-- }
            }
        } }
        yield()
        assertEquals(listOf(0, 1), started)
        jobs[2].cancel(); jobs[2].join()
        gates[0].complete(Unit); jobs[0].join(); yield()
        assertEquals(listOf(0, 1, 3), started)
        assertEquals(2, max)
        gates.forEach { it.complete(Unit) }; jobs.forEach { it.join() }
        AudioDownloadSlots.withSlot { assertEquals(0, active) }
    }

    @Test fun serverCooldownWaitingDoesNotStarveIndependentDownloads() = runBlocking {
        val serverAdmits = CompletableDeferred<Unit>()
        val releaseTransfers = CompletableDeferred<Unit>()
        val bothIndependentTransfersEntered = CompletableDeferred<Unit>()
        var currentTransfers = 0
        var serverTransferStarted = false

        val waitingYouTube = launch {
            withAudioDownloadSlotAfterAdmission(
                awaitAdmission = { serverAdmits.await() },
            ) {
                serverTransferStarted = true
            }
        }
        yield()

        val independent = List(2) {
            launch {
                AudioDownloadSlots.withSlot {
                    currentTransfers++
                    if (currentTransfers == 2) bothIndependentTransfersEntered.complete(Unit)
                    try {
                        releaseTransfers.await()
                    } finally {
                        currentTransfers--
                    }
                }
            }
        }
        withTimeout(1_000L) { bothIndependentTransfersEntered.await() }
        assertEquals(2, currentTransfers)
        assertFalse(serverTransferStarted)

        waitingYouTube.cancel()
        waitingYouTube.join()
        releaseTransfers.complete(Unit)
        independent.forEach { it.join() }
        assertEquals(0, currentTransfers)
        AudioDownloadSlots.withSlot { assertFalse(serverTransferStarted) }
    }
}
