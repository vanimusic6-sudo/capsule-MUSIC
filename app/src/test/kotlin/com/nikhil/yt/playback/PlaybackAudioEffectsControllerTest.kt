package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAudioEffectsControllerTest {
    @Test
    fun resampleReturnsEmptyForNonPositiveTarget() {
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), 0))
        assertEquals(emptyList<Int>(), resampleEqLevelsByIndex(listOf(1, 2), -1))
    }

    @Test
    fun resampleFillsSilenceForMissingLevels() {
        assertEquals(listOf(0, 0, 0), resampleEqLevelsByIndex(emptyList(), 3))
    }

    @Test
    fun resampleKeepsAlreadyMatchingBandCount() {
        val levels = listOf(-100, 0, 100)
        assertEquals(levels, resampleEqLevelsByIndex(levels, 3))
    }

    @Test
    fun resampleAveragesWhenTargetHasSingleBand() {
        assertEquals(listOf(0), resampleEqLevelsByIndex(listOf(-300, 0, 300), 1))
    }

    @Test
    fun resampleInterpolatesAcrossTargetBands() {
        assertEquals(
            listOf(0, 500, 1000, 1500, 2000),
            resampleEqLevelsByIndex(listOf(0, 1000, 2000), 5),
        )
    }
}
