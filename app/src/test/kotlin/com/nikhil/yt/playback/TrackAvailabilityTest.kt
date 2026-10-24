package com.nikhil.yt.playback

import com.metrolist.innertubex.extraction.StreamResolveException
import org.junit.Assert.*
import org.junit.Test

class TrackAvailabilityTest {
    @Test fun networkBotAndGenericFailuresNeverGreyATrack() {
        val store = TrackAvailabilityStore()
        for (kind in listOf(PlaybackFailureKind.NETWORK, PlaybackFailureKind.BOT_CHECK, PlaybackFailureKind.GENERIC)) {
            store.record("track", kind)
            assertTrue(store.states.value.isEmpty())
        }
    }
    @Test fun successfulResolveAndSessionChangeRemoveOldRestrictions() {
        val store = TrackAvailabilityStore()
        store.record("track", PlaybackFailureKind.UNAVAILABLE)
        assertEquals(PlaybackFailureKind.UNAVAILABLE, store.states.value["track"])
        store.clear("track")
        assertTrue(store.states.value.isEmpty())
        store.record("track", PlaybackFailureKind.AUTH_REQUIRED)
        store.clearAll()
        assertTrue(store.states.value.isEmpty())
    }
    @Test fun onlyTypedSourceUnavailabilityCountsAndMemoryIsBounded() {
        val failure = StreamResolveException(StreamResolveException.Reason.UNAVAILABLE, "Unavailable")
        assertEquals(PlaybackFailureKind.UNAVAILABLE, PlaybackFailureClassifier.classify(failure, false))
        val store = TrackAvailabilityStore(2)
        for (id in listOf("old", "retained", "new")) store.record(id, PlaybackFailureKind.UNAVAILABLE)
        assertEquals(setOf("retained", "new"), store.states.value.keys)
    }
}
