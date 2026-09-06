package com.nikhil.yt.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherGuestControlCoordinatorTest {
    private fun roomState(
        index: Int = 0,
        playing: Boolean = false,
        trackIds: List<String> = listOf("a", "b", "c"),
    ) =
        TogetherRoomState(
            sessionId = "sid",
            hostId = "host",
            queue = trackIds.map { TogetherTrack(id = it, title = it) },
            currentIndex = index,
            isPlaying = playing,
        )

    @Test
    fun duplicateControlWithinWindowIsSuppressed() {
        val coordinator = TogetherGuestControlCoordinator()
        val action = ControlAction.Play

        assertTrue(coordinator.registerOutgoing(action, nowElapsedMs = 1_000L, isOnlineSession = false))
        assertFalse(coordinator.registerOutgoing(action, nowElapsedMs = 1_349L, isOnlineSession = false))
        assertTrue(coordinator.registerOutgoing(action, nowElapsedMs = 1_350L, isOnlineSession = false))
    }

    @Test
    fun localSeekMismatchIsHeldUntilTimeoutThenAppliedWithNotice() {
        val coordinator = TogetherGuestControlCoordinator()
        assertTrue(
            coordinator.registerOutgoing(
                ControlAction.SeekToIndex(index = 2),
                nowElapsedMs = 1_000L,
                isOnlineSession = false,
            ),
        )

        val beforeTimeout = coordinator.reconcile(roomState(index = 0), nowElapsedMs = 2_999L)
        assertFalse(beforeTimeout.applyRemoteState)
        assertFalse(beforeTimeout.notifySongChangeFailure)

        val atTimeout = coordinator.reconcile(roomState(index = 0), nowElapsedMs = 3_000L)
        assertTrue(atTimeout.applyRemoteState)
        assertTrue(atTimeout.notifySongChangeFailure)
    }

    @Test
    fun onlineSeekUsesLongerTimeout() {
        val coordinator = TogetherGuestControlCoordinator()
        assertTrue(
            coordinator.registerOutgoing(
                ControlAction.SeekToTrack(trackId = "c"),
                nowElapsedMs = 2_000L,
                isOnlineSession = true,
            ),
        )

        val beforeTimeout = coordinator.reconcile(roomState(index = 0), nowElapsedMs = 6_999L)
        assertFalse(beforeTimeout.applyRemoteState)

        val atTimeout = coordinator.reconcile(roomState(index = 0), nowElapsedMs = 7_000L)
        assertTrue(atTimeout.applyRemoteState)
        assertTrue(atTimeout.notifySongChangeFailure)
    }

    @Test
    fun matchingAuthoritativeStateClearsPendingImmediately() {
        val coordinator = TogetherGuestControlCoordinator()
        assertTrue(
            coordinator.registerOutgoing(
                ControlAction.SeekToTrack(trackId = " b "),
                nowElapsedMs = 1_000L,
                isOnlineSession = true,
            ),
        )

        val matching = coordinator.reconcile(roomState(index = 1), nowElapsedMs = 1_050L)
        assertTrue(matching.applyRemoteState)
        assertFalse(matching.notifySongChangeFailure)

        val laterDifferentState = coordinator.reconcile(roomState(index = 0), nowElapsedMs = 1_100L)
        assertTrue(laterDifferentState.applyRemoteState)
    }

    @Test
    fun playTimeoutDoesNotReportSongChangeFailure() {
        val coordinator = TogetherGuestControlCoordinator()
        assertTrue(coordinator.registerOutgoing(ControlAction.Play, nowElapsedMs = 100L, isOnlineSession = false))

        val decision = coordinator.reconcile(roomState(playing = false), nowElapsedMs = 2_100L)
        assertTrue(decision.applyRemoteState)
        assertFalse(decision.notifySongChangeFailure)
    }

    @Test
    fun resetClearsDedupeAndPendingState() {
        val coordinator = TogetherGuestControlCoordinator()
        val action = ControlAction.Pause

        assertTrue(coordinator.registerOutgoing(action, nowElapsedMs = 5_000L, isOnlineSession = false))
        coordinator.reset()
        assertTrue(coordinator.registerOutgoing(action, nowElapsedMs = 5_001L, isOnlineSession = false))
    }
}
