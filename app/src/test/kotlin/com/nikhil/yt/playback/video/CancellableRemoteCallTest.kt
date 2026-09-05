package com.nikhil.yt.playback.video

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancellableRemoteCallTest {
    @Test fun cancelledQueuedRequestNeverStartsItsRemoteResolve() = runBlocking {
        val queue = mutableListOf<Runnable>()
        var calls = 0
        var cancels = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancellableRemoteCall(Executor { queue.add(it) }, Executor { it.run() },
                call = { calls++ }, cancel = { cancels++ })
        }
        job.cancelAndJoin()
        queue.forEach { it.run() }
        assertEquals(0, calls)
        assertEquals(1, cancels)
    }

    @Test fun cancellationReachesAnAlreadyRunningRemoteCallAndDiscardsItsResult() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val stop = CountDownLatch(1)
        var published = false
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                cancellableRemoteCall(executor, Executor { it.run() }, call = {
                    started.countDown()
                    check(stop.await(3, TimeUnit.SECONDS))
                    "late result"
                }, cancel = { stop.countDown() })
                published = true
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertEquals(0L, stop.count)
            assertFalse(published)
        } finally {
            stop.countDown()
            executor.shutdownNow()
        }
    }
}
