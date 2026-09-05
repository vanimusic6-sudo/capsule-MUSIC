package com.nikhil.yt.innertube.video

import okhttp3.Call
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cancellation crosses Binder threads; ThreadLocal binds NewPipe's synchronous calls to one request. */
object VideoExtractionRequests {
    private class State {
        val createdAtNanos = System.nanoTime()
        val calls = mutableSetOf<Call>()
        var running = false
        var cancelled = false
        var timedOut = false

        @Synchronized
        fun checkActive() {
            if (timedOut) throw SocketTimeoutException("VIDEO extraction timed out")
            if (cancelled) throw InterruptedIOException("VIDEO extraction cancelled")
        }

        @Synchronized
        fun register(call: Call) {
            checkActive()
            calls.add(call)
        }

        @Synchronized
        fun unregister(call: Call) { calls.remove(call) }

        @Synchronized
        fun cancel(timeout: Boolean = false) {
            cancelled = true
            timedOut = timedOut || timeout
            calls.forEach(Call::cancel)
        }
    }

    private val requests = ConcurrentHashMap<String, State>()
    private val current = ThreadLocal<State>()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "capsule-video-deadline").apply { isDaemon = true }
    }

    fun cancel(requestId: String) {
        prune()
        // Keep a short-lived tombstone if cancellation arrives before METHOD_RESOLVE.
        requests.computeIfAbsent(requestId) { State() }.cancel()
    }

    fun <T> withRequest(requestId: String, timeoutMs: Long, block: () -> T): T {
        prune()
        val state = requests.computeIfAbsent(requestId) { State() }
        synchronized(state) {
            check(!state.running) { "Duplicate VIDEO request id" }
            state.checkActive()
            state.running = true
        }
        current.set(state)
        val timeout = timer.schedule({ state.cancel(timeout = true) }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val result = block()
            state.checkActive()
            return result
        } finally {
            timeout.cancel(false)
            state.cancel()
            current.remove()
            requests.remove(requestId, state)
        }
    }

    internal fun <T> execute(call: Call, block: () -> T): T {
        val state = current.get()
        state?.register(call)
        try {
            val result = block()
            state?.checkActive()
            return result
        } finally {
            state?.unregister(call)
        }
    }

    private fun prune() {
        val oldest = System.nanoTime() - TimeUnit.MINUTES.toNanos(1)
        requests.entries.removeIf { (_, state) ->
            synchronized(state) { !state.running && state.createdAtNanos < oldest }
        }
    }
}
