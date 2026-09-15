package com.nikhil.yt.playback

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
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

    @Test
    fun restorePrefersPersistedSeedAndResetsTransientRuntimeState() {
        val runtime = AutomixRuntime()
        val oldJob = Job()
        runtime.job = oldJob
        runtime.loading.value = true
        runtime.error.value = "old-error"
        runtime.autoAddedMediaIds += "stale-auto"

        runtime.restore(
            restoredItems = emptyList(),
            persistedSeedMediaId = "  persisted-seed  ",
            fallbackSeedMediaId = "fallback-seed",
            restoredAutoAddedMediaIds = listOf(" auto-a ", "", "auto-b", "auto-a"),
        )

        assertTrue(oldJob.isCancelled)
        assertNull(runtime.job)
        assertEquals("persisted-seed", runtime.seedMediaId)
        assertFalse(runtime.loading.value)
        assertNull(runtime.error.value)
        assertEquals(setOf("auto-a", "auto-b"), synchronized(runtime.autoAddedMediaIds) { runtime.autoAddedMediaIds.toSet() })
    }

    @Test
    fun restoreFallsBackToPersistedQueueSeedWhenLegacyAutomixHasNoSeed() {
        val runtime = AutomixRuntime()

        runtime.restore(
            restoredItems = emptyList(),
            persistedSeedMediaId = "   ",
            fallbackSeedMediaId = " queue-current ",
        )

        assertEquals("queue-current", runtime.seedMediaId)
        assertTrue(runtime.autoAddedMediaIds.isEmpty())
    }
}
