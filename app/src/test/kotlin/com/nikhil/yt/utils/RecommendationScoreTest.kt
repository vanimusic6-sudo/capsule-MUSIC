package com.nikhil.yt.utils

import org.junit.Assert.*
import org.junit.Test

class RecommendationScoreTest {
    private fun score(time: Long = 180_000L, duration: Int = 180, skips: Int = 0, liked: Boolean = false) =
        RecommendationScore.calculate(time, duration, skips, liked, recent = false, timeOfDay = "afternoon")

    @Test fun feedbackCanOutweighListeningInsteadOfCompetingWithMilliseconds() {
        assertTrue(score(liked = true) > score(time = 900_000L))
        assertTrue(score(time = 900_000L, skips = 2) < score())
    }

    @Test fun equivalentPlaysAreComparableAcrossSongLengths() {
        assertEquals(score(), score(time = 60_000L, duration = 60), 0.001f)
    }

    @Test fun corruptedOrHugeCountersDoNotDominateForever() {
        assertEquals(0f, score(time = -1L), 0f)
        assertTrue(score(time = Long.MAX_VALUE) <= 12f)
        assertEquals(0f, score(time = Long.MAX_VALUE, skips = Int.MAX_VALUE), 0f)
        assertTrue(score(duration = -1).isFinite())
    }
}
