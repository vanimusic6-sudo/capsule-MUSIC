package com.nikhil.yt.playback

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixRuntimeTest {
    @Test
    fun clearCancelsWorkAndResetsAutomixStateWithoutForgettingQueueOwnership() {
        val runtime = AutomixRuntime()
        val job = Job()

        runtime.job = job
        runtime.seedMediaId = "seed"
        runtime.loading.value = true
        runtime.error.value = "boom"
        runtime.autoAddedMediaIds += "auto-added"

        runtime.clear()

        assertTrue(job.isCancelled)
        assertNull(runtime.job)
        assertNull(runtime.seedMediaId)
        assertTrue(runtime.items.value.isEmpty())
        assertFalse(runtime.loading.value)
        assertNull(runtime.error.value)
        assertTrue(runtime.autoAddedMediaIds.contains("auto-added"))
    }

    @Test
    fun jobLookupOnlyReturnsWorkForMatchingSeed() {
        val runtime = AutomixRuntime()
        val job = Job()
        runtime.job = job
        runtime.seedMediaId = "current"

        assertSame(job, runtime.jobForSeed("current"))
        assertNull(runtime.jobForSeed("stale"))

        job.cancel()
    }

    @Test
    fun activeJobCountsAsCurrentEvenBeforeItemsArrive() {
        val runtime = AutomixRuntime()
        val job = Job()
        runtime.job = job
        runtime.seedMediaId = "seed"

        assertTrue(runtime.hasItemsOrActiveJobFor("seed"))
        assertFalse(runtime.hasItemsOrActiveJobFor("other"))

        job.cancel()
        assertFalse(runtime.hasItemsOrActiveJobFor("seed"))
    }
}
