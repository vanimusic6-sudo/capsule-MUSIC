package com.nikhil.yt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleArtworkTransitionTest {
    @Test
    fun nextTrackPushesFromRight() {
        assertEquals(1, capsuleArtworkSlideDirection(fromIndex = 3, toIndex = 4))
    }

    @Test
    fun previousTrackPushesFromLeft() {
        assertEquals(-1, capsuleArtworkSlideDirection(fromIndex = 4, toIndex = 3))
    }

    @Test
    fun unknownOrReplacedQueueDefaultsForward() {
        assertEquals(1, capsuleArtworkSlideDirection(fromIndex = -1, toIndex = 2))
        assertEquals(1, capsuleArtworkSlideDirection(fromIndex = 2, toIndex = 2))
    }
}
