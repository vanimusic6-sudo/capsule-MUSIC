/* Capsule MUSIC — one paced admission for one serial VIDEO operation. GPL-3.0. */
package com.nikhil.yt.innertube

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Search and extraction are stages of one request, not two independent UI requests. */
class CapsuleVideoMetadataAdmission(
    private val admit: suspend () -> Unit = CapsuleVideoRequestGuard::beforeMetadataRequest,
) {
    // A slow first-party counterpart probe may overlap one speculative
    // anonymous VIDEO search. These stages still share ONE admission gate.
    // Set admitted only after a successful permit: cancelled/failed admission
    // must never silently bypass the YouTube cooldown on the next request.
    private val mutex = Mutex()
    private var admitted = false

    suspend fun beforeRequest() {
        mutex.withLock {
            if (!admitted) {
                admit()
                admitted = true
            }
        }
    }
}
