/* Capsule MUSIC — independent, anonymous direct VIDEO route. GPL-3.0. */
package com.nikhil.yt.innertube.video

import com.nikhil.yt.innertube.buildPlayerRequestPayload
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeLocale
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * One maintained client, one request, no watch page or JavaScript bootstrap.
 * Only complete direct URLs are accepted. Cipher, n-challenge, HLS and SABR
 * responses go to NewPipe rather than being misrepresented as playable MP4.
 * This client/pool/session exists only in :capsule_video; AUDIO is never used.
 */
object CapsuleDirectVideoExtractor {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun resolve(videoId: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean): CapsuleNewPipeResolved? {
        val identity = YouTubeClient.VISIONOS
        val payload = buildPlayerRequestPayload(
            client = identity, locale = YouTubeLocale("US", "en"),
            visitorData = null, dataSyncId = null, videoId = videoId,
            playlistId = null, signatureTimestamp = null, poToken = null,
        )
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", identity.userAgent)
            .header("X-YouTube-Client-Name", identity.clientId)
            .header("X-YouTube-Client-Version", identity.clientVersion)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        return VideoExtractionRequests.execute(call) {
            call.execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("VIDEO HTTP 429", request.url.toString())
                if (!response.isSuccessful) throw IOException("VIDEO direct player HTTP ${response.code}")
                selectDirectVideo(Json.parseToJsonElement(response.body.string()).jsonObject, videoId, quality, muxedOnly)
            }
        }
    }
}

internal fun selectDirectVideo(
    response: JsonObject, videoId: String, quality: CapsuleNewPipeQuality,
    muxedOnly: Boolean = false, nowMs: Long = System.currentTimeMillis(),
): CapsuleNewPipeResolved? {
    if ((response["playabilityStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.content != "OK") return null
    val returnedId = (response["videoDetails"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull
    if (returnedId != null && returnedId != videoId) return null
    val data = response["streamingData"] as? JsonObject ?: return null
    fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.intOrNull
    fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    fun direct(format: JsonObject): Boolean {
        if (format["signatureCipher"] != null || format["cipher"] != null) return false
        val uri = runCatching { URI(format.string("url")) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https" || !host.endsWith(".googlevideo.com")) return false
        val query = uri.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        // A baked range is a partial resource, not a complete source. An n
        // challenge must be solved by the upstream extractor, not ignored.
        if (query.containsKey("n") || query.containsKey("range")) return false
        val expires = query["expire"]?.toLongOrNull() ?: return false
        return expires > nowMs / 1000L + 45L
    }
    fun formats(name: String) = (data[name] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.filter(::direct)
    fun choose(items: List<JsonObject>) = items.filter {
        (it.number("height") ?: 0) in 1..quality.maxHeight &&
            it.string("mimeType")?.startsWith("video/mp4") == true
    }.maxWithOrNull(compareBy<JsonObject> { it.number("height") ?: 0 }
        .thenBy { -(it.number("fps") ?: 30) }.thenBy { it.number("bitrate") ?: 0 })
    val muxed = choose(formats("formats").filter { (it.number("audioChannels") ?: 0) > 0 || it.string("audioQuality") != null })
    val adaptive = formats("adaptiveFormats")
    val audio = adaptive.filter { it.string("mimeType")?.startsWith("audio/mp4") == true }
        .maxByOrNull { it.number("bitrate") ?: 0 }
    val video = if (muxedOnly || quality == CapsuleNewPipeQuality.AUTO || quality == CapsuleNewPipeQuality.P360) {
        muxed ?: if (!muxedOnly && audio != null) choose(adaptive) else null
    } else {
        (if (audio != null) choose(adaptive) else null) ?: muxed
    } ?: return null
    val soundtrack = if (video === muxed) null else audio ?: return null
    val urls = listOfNotNull(video.string("url"), soundtrack?.string("url"))
    val expiresAt = urls.minOf { url ->
        URI(url).rawQuery.split('&').first { it.startsWith("expire=") }.substringAfter('=').toLong() * 1000L
    }
    return CapsuleNewPipeResolved(
        videoId, video.string("url")!!, soundtrack?.string("url"),
        video.string("qualityLabel") ?: "${video.number("height")}p",
        video.number("width") ?: 0, video.number("height") ?: 0,
        video.number("itag") ?: -1, soundtrack?.number("itag") ?: -1, expiresAt,
    )
}
