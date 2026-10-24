package com.nikhil.yt.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
}
