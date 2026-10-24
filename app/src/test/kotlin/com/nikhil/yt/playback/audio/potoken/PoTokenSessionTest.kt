package com.nikhil.yt.playback.audio.potoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PoTokenSessionTest {
    private class Minter : PoTokenMinter {
        override var isDead = false
        override var isExpired = false
        var closed = false
        val identifiers = mutableListOf<String>()
        var mint: suspend (String) -> String = { "token:$it" }
        override suspend fun generatePoToken(identifier: String): String {
            identifiers += identifier
            return mint(identifier)
        }
        override fun close() { closed = true }
    }

    @Test fun simultaneousWarmupAndPlaybackCreateOneVisitorSession() = runTest {
        var creates = 0
        val minter = Minter().apply { mint = { delay(10); "token:$it" } }
        val session = PoTokenSession({ creates++; minter }, { testScheduler.currentTime })
        val warmup = async { session.prewarm("visitor") }
        val playback = (1..20).map { index -> async { session.obtain("video$index", "visitor") } }
        assertTrue(warmup.await())
        playback.awaitAll().forEachIndexed { index, token ->
            assertEquals("token:visitor", token?.playerRequestPoToken)
            assertEquals("token:video${index + 1}", token?.streamingDataPoToken)
        }
        assertEquals(1, creates)
        assertEquals(1, minter.identifiers.count { it == "visitor" })
    }

    @Test fun failedWarmupsAreSpacedAndStopAfterThreeAttempts() = runTest {
        var creates = 0
        val session = PoTokenSession({ creates++; throw PoTokenException("offline") }, { testScheduler.currentTime })
        repeat(20) { assertFalse(session.prewarm("visitor")) }
        assertEquals(1, creates)
        assertFalse(session.mayPrepare("visitor"))
        advanceTimeBy(30_000)
        assertFalse(session.prewarm("visitor"))
        assertEquals(2, creates)
        advanceTimeBy(120_000)
        assertFalse(session.prewarm("visitor"))
        advanceTimeBy(3_600_000)
        repeat(20) { assertNull(session.obtain("video", "visitor")) }
        assertEquals(3, creates)
        assertFalse(session.mayPrepare("visitor"))
        assertTrue(session.mayPrepare("new-visitor"))
        assertFalse(session.prewarm("new-visitor"))
        assertEquals(4, creates)
    }

    @Test fun timeoutDisposesUnpublishedMinterAndDoesNotRetryImmediately() = runTest {
        var creates = 0
        val minter = Minter().apply { mint = { delay(20_000); "late" } }
        val session = PoTokenSession({ creates++; minter }, { testScheduler.currentTime })
        assertFalse(session.prewarm("visitor"))
        assertTrue(minter.closed)
        assertFalse(session.isReady("visitor"))
        assertFalse(session.prewarm("visitor"))
        assertEquals(1, creates)
    }

    @Test fun cancellingWaiterCannotClearTheOwnersHealthySession() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val minter = Minter().apply {
            mint = { id ->
                if (id == "video") { entered.complete(Unit); release.await() }
                "token:$id"
            }
        }
        val session = PoTokenSession({ minter })
        assertTrue(session.prewarm("visitor"))
        val owner = async { session.obtain("video", "visitor") }
        entered.await()
        val waiter = launch { session.prewarm("other-visitor") }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertNotNull(owner.await())
        assertTrue(session.isReady("visitor"))
        assertFalse(minter.closed)
    }

    @Test fun cancellingOwnerDisposesSessionAndPropagatesCancellation() = runTest {
        val minter = Minter().apply { mint = { delay(20_000); "late" } }
        val session = PoTokenSession({ minter })
        val owner = launch { session.prewarm("visitor") }
        runCurrent()
        owner.cancelAndJoin()
        assertTrue(owner.isCancelled)
        assertTrue(minter.closed)
        assertFalse(session.isReady("visitor"))
    }

    @Test fun visitorChangeAndExpiryReplaceSessionWithoutReusingBoundTokens() = runTest {
        val minters = mutableListOf<Minter>()
        val session = PoTokenSession({ Minter().also { minters += it } })
        assertTrue(session.prewarm("first"))
        assertTrue(session.prewarm("first"))
        assertEquals(1, minters.size)
        assertTrue(session.prewarm("second"))
        assertTrue(minters.first().closed)
        assertFalse(session.isReady("first"))
        minters.last().isExpired = true
        assertFalse(session.isReady("second"))
        assertNotNull(session.obtain("video", "second"))
        assertEquals(3, minters.size)
        session.close()
        assertTrue(minters.last().closed)
        assertFalse(session.isReady("second"))
    }

    @Test fun unsupportedWebViewStopsFurtherRequests() = runTest {
        var creates = 0
        val session = PoTokenSession({ creates++; throw BadWebViewException("unsupported") })
        assertFalse(session.prewarm("visitor"))
        assertFalse(session.prewarm("different-visitor"))
        assertEquals(1, creates)
    }
}
