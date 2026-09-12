package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Observe the wire response before InnerTubeX retries it or discards playability reasons. */
internal class CapsuleAudioRequestInterceptor(private val guardStreams: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host != "youtube.com" && !host.endsWith(".youtube.com") &&
            host != "youtubei.googleapis.com" &&
            !(guardStreams && (host == "googlevideo.com" || host.endsWith(".googlevideo.com")))
        ) return chain.proceed(request)

        CapsulePlaybackSafety.blockedExceptionOrNull()?.let {
            // OkHttp interceptors must throw IOException, not an uncaught runtime exception.
            throw IOException(it.message, it)
        }
        val response = chain.proceed(request)
        val signal =
            if (response.code == 429) YouTubeFailureKind.RATE_LIMITED
            else if (request.url.encodedPath.endsWith("/player")) {
                val body = try { response.peekBody(MAX_ERROR_BODY_BYTES).string() }
                catch (failure: IOException) { response.close(); throw failure }
                playabilityFailure(body)
            } else YouTubeFailureKind.NONE

        when (signal) {
            YouTubeFailureKind.RATE_LIMITED -> CapsulePlaybackSafety.markHttpStatusFailure(429)
            // Preserve the challenge even if InnerTubeX later summarizes it as NO_PLAYABLE_STREAM.
            // The serialized resolver knows which profile caused it and owns safe escalation.
            YouTubeFailureKind.BOT_CHECK -> CapsulePlaybackSafety.noteWireBotCheck()
            else -> Unit
        }
        // HTTP 429 has already opened the global gate. Bot-check escalation is profile-aware.
        return response
    }

    private fun playabilityFailure(body: String): YouTubeFailureKind {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val status = root?.get("playabilityStatus") as? JsonObject ?: return YouTubeFailureKind.NONE
        val name = (status["status"] as? JsonPrimitive)?.contentOrNull
        if (name == "OK") return YouTubeFailureKind.NONE
        return YouTubeFailureClassifier.classify(
            playabilityStatus = name,
            // Only error fields are inspected; titles and successful playback metadata
            // containing words such as "not a bot" must never open the breaker.
            text = listOfNotNull(status["reason"], status["messages"], status["errorScreen"])
                .joinToString(" "),
        )
    }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 64 * 1024L
    }
}
