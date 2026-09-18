package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.utils.GlobalLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
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
                describePlayerResponse(body)
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

    /**
     * What kind of item YouTube just described, in terms that separate a track from an upload.
     *
     * The 403s cluster on the results of the Videos tab — fan compilations, remixes, re-uploads —
     * while library tracks open first time. Nothing the app already records tells the two apart:
     * same itag, same client, same CDN hosts, a fresh signed link with a token, and the same host
     * serving 206 to one and 403 to the next. The difference, if there is one, is in the response
     * that produced the link, and this is the part of it that could hold it.
     *
     * `musicVideoType` is the field to watch. An official track carries one; an arbitrary upload
     * does not. If every refusal turns out to be an item without it, then the app is asking the
     * music client for something that is not in the music catalogue, and that is the answer.
     *
     * Titles and channel names are deliberately absent. These are flags and short status words —
     * enough to classify an item, not enough to say which one it was.
     */
    private fun describePlayerResponse(body: String) {
        if (!GlobalLog.isEnabled) return
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
        val playability = root["playabilityStatus"] as? JsonObject
        val details = root["videoDetails"] as? JsonObject
        val streaming = root["streamingData"] as? JsonObject
        val microformat =
            (root["microformat"] as? JsonObject)
                ?.get("playerMicroformatRenderer") as? JsonObject

        fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull

        /*
         * Info, not debug. The first capture taken with this in place carried no debug lines at
         * all — the export drops them — so the one line that was added to answer the question was
         * the one line missing from the answer. It fires once per player response, the same cadence
         * as "stream selected" beside it, so it costs a line per track and not a line per read.
         */
        Timber.tag("PlayerItem").i(
            "player-item status=%s musicVideoType=%s live=%s private=%s unlisted=%s " +
                "embeddable=%s expiresInSec=%s adaptiveFormats=%d progressiveFormats=%d",
            playability.text("status") ?: "none",
            details.text("musicVideoType") ?: microformat.text("musicVideoType") ?: "none",
            details.text("isLiveContent") ?: "?",
            details.text("isPrivate") ?: "?",
            microformat.text("isUnlisted") ?: "?",
            playability.text("playableInEmbed") ?: "?",
            streaming.text("expiresInSeconds") ?: "?",
            (streaming?.get("adaptiveFormats") as? JsonArray)?.size ?: -1,
            (streaming?.get("formats") as? JsonArray)?.size ?: -1,
        )
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
