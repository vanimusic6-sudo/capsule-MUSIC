package com.nikhil.yt.playback

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures service teardown schedules Together network shutdown only once. */
internal class TogetherShutdownGate {
    private val started = AtomicBoolean(false)

    fun tryBegin(): Boolean = started.compareAndSet(false, true)
}
