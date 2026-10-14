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
 * Checks server cooldown and HTTP-status feedback for actual VIDEO media bytes.
 * Metadata/search/extractor requests are guarded in the innertube layer; this
 * HLS playlists/segments and progressive ranges have no finite local quota and
 * do not pace or consume metadata/search reservations.
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

        val guardEpoch = CapsuleVideoRequestGuard.resetEpoch()
        val response = chain.proceed(chain.request())
        CapsuleVideoRequestGuard.noteStreamStatus(response.code, guardEpoch)
        return response
    }
}
