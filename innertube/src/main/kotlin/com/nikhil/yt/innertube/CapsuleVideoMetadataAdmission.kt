/* Capsule MUSIC — one paced admission for one serial VIDEO operation. GPL-3.0. */
package com.nikhil.yt.innertube

/** Search and extraction are stages of one request, not two independent UI requests. */
class CapsuleVideoMetadataAdmission(
    private val admit: suspend () -> Unit = CapsuleVideoRequestGuard::beforeMetadataRequest,
) {
    private var admitted = false
    suspend fun beforeRequest() {
        if (!admitted) {
            admit()
            admitted = true
        }
    }
}
