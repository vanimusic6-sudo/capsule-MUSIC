package com.nikhil.yt.playback

import org.junit.Assert.*
import org.junit.Test

class SongLikeMutationFenceTest {
    @Test fun aRemoteLikeSnapshotCannotRestoreALocalUnlikeAcceptedWhileItWasLoading() {
        val fence = SongLikeMutationFence()
        val remoteRequest = fence.snapshot()
        fence.record("song")
        assertTrue(fence.changedSince("song", remoteRequest))
    }

    @Test fun aDifferentSongsTapDoesNotBlockThisSongsRemoteUpdate() {
        val fence = SongLikeMutationFence()
        val remoteRequest = fence.snapshot()
        fence.record("other-song")
        assertFalse(fence.changedSince("song", remoteRequest))
    }

    @Test fun aNewRequestCanReconcileStateThatAlreadyExistedWhenItStarted() {
        val fence = SongLikeMutationFence()
        fence.record("song")
        val remoteRequest = fence.snapshot()
        assertFalse(fence.changedSince("song", remoteRequest))
        fence.record("song")
        assertTrue(fence.changedSince("song", remoteRequest))
    }
}
