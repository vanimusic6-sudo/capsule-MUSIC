package com.nikhil.yt.innertube.utils

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompletePaginationTest {
    @Test fun failedSecondPageCannotBecomeASuccessfulPartialCollection() = runTest {
        val failure = IOException("Page 2 failed")
        try {
            collectCompletePages(listOf("saved"), "page2") { throw failure }
            fail("Partial collection escaped")
        } catch (actual: IOException) { assertSame(failure, actual) }
    }

    @Test fun cancellationPropagatesWithoutPublishingPartialItems() = runTest {
        val cancelled = CancellationException("Disabled sync")
        try {
            collectCompletePages(listOf("saved"), "page2") { throw cancelled }
            fail("Cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun emptyIntermediatePagesAreAllowedAndOrderAndRepeatedSongsSurvive() = runTest {
        val pages = mapOf("a" to (emptyList<String>() to "b"), "b" to (listOf("first", "last") to null))
        assertEquals(listOf("first", "first", "last"), collectCompletePages(listOf("first"), "a") { pages.getValue(it) })
    }

    @Test fun cyclesAndBudgetExhaustionFailClosed() = runTest {
        for (cycle in listOf(true, false)) {
            var requests = 0
            try {
                collectCompletePages(emptyList<String>(), "a", maxRequests = 2) {
                    requests++
                    listOf("item") to if (cycle) "a" else requests.toString()
                }
                fail("Incomplete collection escaped")
            } catch (_: IncompletePaginationException) {
                assertTrue(requests in 1..2)
            }
        }
    }
}
