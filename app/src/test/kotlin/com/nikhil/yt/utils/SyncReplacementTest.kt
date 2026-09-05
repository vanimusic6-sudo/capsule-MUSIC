package com.nikhil.yt.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SyncReplacementTest {
    @Test fun disablingBeforeOrDuringReplacementAlwaysAbortsTheTransactionBlock() = runTest {
        for (stopAfter in 0..3) {
            var active = stopAfter != 0
            val pendingWrites = mutableListOf<String>()
            try {
                replaceSyncedItems(listOf("a", "b", "c"), { active }, { pendingWrites.clear() }) { _, item ->
                    pendingWrites += item
                    if (pendingWrites.size == stopAfter) active = false
                }
                fail("Transaction would have committed after disabling sync at $stopAfter")
            } catch (_: CancellationException) {
                assertEquals(stopAfter, pendingWrites.size)
            }
        }
    }

    @Test fun successfulReplacementRetainsRemoteOrderAndOccurrences() = runTest {
        val writes = mutableListOf("old")
        replaceSyncedItems(listOf("b", "a", "b"), { true }, { writes.clear() }) { index, item ->
            assertEquals(writes.size, index)
            writes += item
        }
        assertEquals(listOf("b", "a", "b"), writes)
    }
}
