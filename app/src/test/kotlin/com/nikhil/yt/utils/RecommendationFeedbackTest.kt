package com.nikhil.yt.utils

import org.junit.Assert.*
import org.junit.Test

class RecommendationFeedbackTest {
    @Test fun recentRepeatedSkipsMatterButANewerLikeAndTimeCanRestoreATrack() {
        assertEquals(3, RecommendationFeedback.effectiveSkips(3, 1_000, null, 2_000))
        assertEquals(0, RecommendationFeedback.effectiveSkips(3, 1_000, 2_000, 3_000))
        assertEquals(0, RecommendationFeedback.effectiveSkips(3, 0, null, 30L * 24 * 60 * 60 * 1_000))
    }

    @Test fun rejectedCandidatesCannotReturnThroughAnotherSeedAndOneArtistCannotFillTheList() {
        val selected = RecommendationFeedback.select(
            listOf("rejected", "a1", "a2", "a3", "a4", "b1", "b1", "rejected"),
            limit = 50,
            id = { it },
            artistIds = { listOf(it.take(1)) },
            rejected = { it == "rejected" },
            score = { 1f },
        )
        assertEquals(listOf("a1", "a2", "a3", "b1"), selected)
    }
}
