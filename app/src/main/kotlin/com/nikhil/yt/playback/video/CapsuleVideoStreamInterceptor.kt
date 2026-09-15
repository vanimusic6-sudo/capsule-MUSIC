/*
 * Capsule MUSIC
 * GPL-3.0
 */

package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Owns request pacing and HTTP-status feedback for actual VIDEO media bytes.
 * Metadata/search/extractor requests are guarded in the innertube layer; this
 * interceptor closes the remaining gap for googlevideo/CDN stream requests.
 */
internal class CapsuleVideoStreamInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            runBlocking {
                CapsuleVideoRequestGuard.beforeStreamProbe()
            }
        } catch (blocked: CapsuleVideoRequestGuard.RequestBlockedException) {
            throw IOException(
                blocked.message ?: "YouTube VIDEO stream requests are temporarily paused",
                blocked,
            )
        }

        val response = chain.proceed(chain.request())
        CapsuleVideoRequestGuard.noteStreamStatus(response.code)
        return response
    }
}
