package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCacheAccountingTest {
    @Test
    fun failedRemovalDoesNotPretendBytesWereFreed() {
        assertEquals(
            120L,
            accountedCacheBytesAfterRemoval(
                totalBytes = 120L,
                removedSizeBytes = 70L,
                removalSucceeded = false,
            ),
        )
    }

    @Test
    fun successfulRemovalSubtractsOnlyRemovedCandidateSize() {
        assertEquals(
            50L,
            accountedCacheBytesAfterRemoval(
                totalBytes = 120L,
                removedSizeBytes = 70L,
                removalSucceeded = true,
            ),
        )
    }

    @Test
    fun accountingNeverBecomesNegative() {
        assertEquals(
            0L,
            accountedCacheBytesAfterRemoval(
                totalBytes = 40L,
                removedSizeBytes = 100L,
                removalSucceeded = true,
            ),
        )
    }

    @Test
    fun negativeCandidateSizeCannotIncreaseOrReduceAccounting() {
        assertEquals(
            40L,
            accountedCacheBytesAfterRemoval(
                totalBytes = 40L,
                removedSizeBytes = -10L,
                removalSucceeded = true,
            ),
        )
    }
}
