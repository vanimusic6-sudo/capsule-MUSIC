package com.nikhil.yt.playback

import android.app.Application
import android.net.Uri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CollectionDownloadStateTest {
    private fun download(id: String, state: Int) = Download(
        DownloadRequest.Builder(id, Uri.parse(id)).build(), state, 0, 0, 100, 0,
        if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE,
    )
    @Test fun emptyAndPartiallyDownloadedCollectionsNeverClaimSuccess() {
        val downloads = mapOf("a" to download("a", Download.STATE_COMPLETED))
        assertEquals(Download.STATE_STOPPED, collectionDownloadState(emptyList(), downloads))
        assertEquals(Download.STATE_STOPPED, collectionDownloadState(listOf("a", "b"), downloads))
        assertEquals(Download.STATE_COMPLETED, collectionDownloadState(listOf("a", "a"), downloads))
    }
    @Test fun membershipCompletionRemovalAndFailureUseTheSameIndex() {
        val complete = mapOf("a" to download("a", Download.STATE_COMPLETED), "b" to download("b", Download.STATE_COMPLETED))
        assertEquals(Download.STATE_COMPLETED, collectionDownloadState(listOf("a", "b"), complete))
        assertEquals(Download.STATE_STOPPED, collectionDownloadState(listOf("a", "b"), complete - "b"))
        assertEquals(Download.STATE_COMPLETED, collectionDownloadState(listOf("a"), complete - "b"))
        assertEquals(Download.STATE_STOPPED, collectionDownloadState(listOf("a", "b"), complete + ("b" to download("b", Download.STATE_FAILED))))
    }
    @Test fun deleteInProgressNeverLooksLikeAResumableOrCompletedCollection() {
        val downloads = mapOf(
            "a" to download("a", Download.STATE_REMOVING),
            "b" to download("b", Download.STATE_COMPLETED),
        )
        assertEquals(Download.STATE_REMOVING, collectionDownloadState(listOf("a", "b"), downloads))
        assertEquals(Download.STATE_STOPPED, collectionDownloadState(listOf("a", "b"), emptyMap()))
    }

    @Test fun oneActiveTrackKeepsABatchVisibleEvenWhenAnotherFailed() {
        for (active in listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)) {
            val downloads = mapOf("a" to download("a", active), "b" to download("b", Download.STATE_FAILED))
            assertEquals(Download.STATE_DOWNLOADING, collectionDownloadState(listOf("a", "b"), downloads))
        }
    }
}
