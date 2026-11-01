package com.nikhil.yt.innertube

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicVideoCachePolicyTest {
    @Test fun importedOldTwelveHourNegativeExpiresWithinNewFifteenMinuteBudget() {
        val now = System.currentTimeMillis()
        val mediaId = "test-legacy-negative"
        try {
            YouTubeMusicVideoLinkResolver.importCache(listOf(
                YouTubeMusicVideoLinkResolver.CacheRow(
                    sourceMediaId = mediaId, videoId = null,
                    title = null, score = 0,
                    expiresAtMs = now + 12L * 60L * 60L * 1000L,
                ),
            ))
            val row = YouTubeMusicVideoLinkResolver.exportCache()
                .single { it.sourceMediaId == mediaId }
            assertNull(row.videoId)
            assertTrue(row.expiresAtMs > now)
            assertTrue(row.expiresAtMs <= System.currentTimeMillis() + 15L * 60L * 1000L)
        } finally {
            YouTubeMusicVideoLinkResolver.forget(mediaId)
        }
    }

    @Test fun aVerifiedCachedVideoKeepsItsExistingPositiveExpiry() {
        val now = System.currentTimeMillis()
        val mediaId = "test-positive"
        val expiry = now + 6L * 60L * 60L * 1000L
        try {
            YouTubeMusicVideoLinkResolver.importCache(listOf(
                YouTubeMusicVideoLinkResolver.CacheRow(
                    sourceMediaId = mediaId, videoId = "trusted-omv",
                    title = "Verified Video", score = 240,
                    expiresAtMs = expiry,
                ),
            ))
            assertEquals(expiry, YouTubeMusicVideoLinkResolver.exportCache()
                .single { it.sourceMediaId == mediaId }.expiresAtMs)
        } finally {
            YouTubeMusicVideoLinkResolver.forget(mediaId)
        }
    }
}
