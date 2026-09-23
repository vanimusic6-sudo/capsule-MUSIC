package com.nikhil.yt.playback

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongRecoveryJobOwnerTest {
    @Test
    fun activeJobDeduplicatesSecondLaunch() {
        val owner = SongRecoveryJobOwner()
        val first = Job()
        var secondCreated = false

        assertTrue(owner.launchIfAbsent("song") { first })
        assertFalse(
            owner.launchIfAbsent("song") {
                secondCreated = true
                Job()
            },
        )

        assertFalse(secondCreated)
        assertEquals(setOf("song"), owner.trackedIds())
        assertTrue(first.isActive)
    }

    @Test
    fun transitionCancelsEverythingExceptCurrentSong() {
        val owner = SongRecoveryJobOwner()
        val keep = Job()
        val staleA = Job()
        val staleB = Job()

        owner.launchIfAbsent("keep") { keep }
        owner.launchIfAbsent("a") { staleA }
        owner.launchIfAbsent("b") { staleB }

        owner.cancelExcept("keep")

        assertEquals(setOf("keep"), owner.trackedIds())
        assertTrue(keep.isActive)
        assertTrue(staleA.isCancelled)
        assertTrue(staleB.isCancelled)
    }

    @Test
    fun completedJobCanBeScheduledAgain() {
        val owner = SongRecoveryJobOwner()
        val first = Job()

        assertTrue(owner.launchIfAbsent("song") { first })
        first.complete()
        assertTrue(owner.trackedIds().isEmpty())

        val replacement = Job()
        assertTrue(owner.launchIfAbsent("song") { replacement })
        assertEquals(setOf("song"), owner.trackedIds())
        assertTrue(replacement.isActive)
    }

    @Test
    fun cancelAllClearsOwnershipAndCancelsJobs() {
        val owner = SongRecoveryJobOwner()
        val first = Job()
        val second = Job()

        owner.launchIfAbsent("a") { first }
        owner.launchIfAbsent("b") { second }
        owner.cancelAll()

        assertTrue(owner.trackedIds().isEmpty())
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}
