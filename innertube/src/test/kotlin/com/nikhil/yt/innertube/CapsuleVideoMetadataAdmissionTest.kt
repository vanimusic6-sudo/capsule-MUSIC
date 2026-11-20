package com.nikhil.yt.innertube

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoMetadataAdmissionTest {
    @Test fun concurrentVideoStagesCannotBypassAdmissionOrDoubleSpendIt() = runBlocking {
        val held = CompletableDeferred<Unit>()
        var reservations = 0
        val request = CapsuleVideoMetadataAdmission {
            reservations++
            held.await()
        }
        val waiters = List(16) { async { request.beforeRequest() } }
        yield()
        held.complete(Unit)
        waiters.awaitAll()
        assertEquals(1, reservations)
    }

    @Test fun searchFallbackAndExtractionShareOneReservationButNextTrackGetsItsOwn() = runBlocking {
        var reservations = 0
        val request = CapsuleVideoMetadataAdmission { reservations++ }
        request.beforeRequest() // First search.
        request.beforeRequest() // Fallback query.
        request.beforeRequest() // Extract the exact clip.
        assertEquals(1, reservations)
        CapsuleVideoMetadataAdmission { reservations++ }.beforeRequest()
        assertEquals(2, reservations)
    }
    @Test fun rejectedOrCancelledAdmissionDoesNotAuthorizeTheNextAttempt() = runBlocking {
        var attempts = 0
        val request = CapsuleVideoMetadataAdmission {
            attempts++
            if (attempts == 1) throw IllegalStateException("blocked")
        }
        assertTrue(runCatching { request.beforeRequest() }.isFailure)
        request.beforeRequest()
        assertEquals(2, attempts)
    }
}
