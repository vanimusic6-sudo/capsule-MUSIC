package com.nikhil.yt.ui.component

import org.junit.Assert.*
import org.junit.Test

class DetailArtworkRequestStateTest {
    @Test fun decodedCoverSurvivesPaginationReorderingTimeoutAndError() {
        val state = DetailArtworkRequestState(listOf("primary", "track-a"))
        val cached = state.attempt!!
        state.succeed(cached)
        state.updateCandidates(listOf("track-b", "primary", "track-a"))
        state.fail(cached)
        assertTrue(state.loaded)
        assertEquals(cached, state.attempt)
    }

    @Test fun staleSuccessCannotStopTheNextFallbackAndLateErrorCannotSkipIt() {
        val state = DetailArtworkRequestState(listOf("broken-resize", "original", "track"))
        val first = state.attempt!!
        state.fail(first)
        val fallback = state.attempt!!
        state.succeed(first)
        state.fail(first)
        assertFalse(state.loaded)
        assertEquals("original", state.attempt!!.source)
        state.succeed(fallback)
        assertTrue(state.loaded)
    }

    @Test fun slowCoverUsesTheSameBoundedChainAsAnError() {
        val state = DetailArtworkRequestState(listOf("primary", "original", "track"))
        val attempted = mutableListOf<String>()
        while (state.attempt != null) {
            val attempt = state.attempt!!
            attempted += attempt.source
            state.fail(attempt)
            assertTrue("Retries must be finite", attempted.size <= 9)
        }
        assertEquals(List(3) { listOf("primary", "original", "track") }.flatten(), attempted)
        assertFalse(state.loaded)
        state.updateCandidates(listOf("primary", "original", "track"))
        assertNull("Exhausted sources must not poll at rest", state.attempt)
    }

    @Test fun newlyDiscoveredFallbackRecoversEvenWhenCandidateCountDoesNotGrow() {
        val state = DetailArtworkRequestState(listOf("broken"))
        repeat(3) { state.fail(state.attempt!!) }
        state.updateCandidates(listOf("new-cover"))
        assertEquals("new-cover", state.attempt!!.source)
        state.succeed(state.attempt!!)
        assertTrue(state.loaded)
    }

    @Test fun candidateChangesDuringLoadingKeepTheCurrentRequest() {
        val state = DetailArtworkRequestState(listOf("primary", "old-track"))
        val active = state.attempt!!
        state.updateCandidates(listOf("new-track", "primary"))
        assertEquals(active, state.attempt)
        state.fail(active)
        assertEquals("new-track", state.attempt!!.source)
    }

    @Test fun collectionIdentityHasIndependentLoadingAndNoCoverIsTerminalUntilMetadataArrives() {
        val first = DetailArtworkRequestState(listOf("album-a"))
        first.succeed(first.attempt!!)
        val next = DetailArtworkRequestState(emptyList())
        assertNull(next.attempt)
        assertFalse(next.loaded)
        next.updateCandidates(listOf("album-b"))
        assertEquals("album-b", next.attempt!!.source)
        assertEquals("album-a", first.attempt!!.source)
    }
}
