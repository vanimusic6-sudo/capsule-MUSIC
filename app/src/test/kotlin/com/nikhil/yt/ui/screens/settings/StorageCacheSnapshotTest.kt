package com.nikhil.yt.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class StorageCacheSnapshotTest {
    @Test fun validZeroAndPositiveAccountingNeverScanTheDirectory() {
        var scans = 0
        for (bytes in listOf(0L, 10_000L)) {
            assertEquals(bytes, storageCacheBytes({ bytes }, { scans++; 999L }))
        }
        assertEquals(0, scans)
    }

    @Test fun unavailableAccountingStillUsesTheOriginalDiskFallback() {
        var scans = 0
        assertEquals(123L, storageCacheBytes({ error("released") }, { scans++; 123L }))
        assertEquals(1, scans)
    }
}
