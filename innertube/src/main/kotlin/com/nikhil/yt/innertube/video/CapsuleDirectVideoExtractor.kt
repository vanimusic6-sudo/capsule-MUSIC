/* Capsule MUSIC — independent, anonymous direct VIDEO routes.
 * Android Reel request adapted from PipePipeExtractor (see method attribution).
 * Copyright (C) Christian Schabesberger 2019; NewPipe/PipePipe contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nikhil.yt.innertube.video

import com.nikhil.yt.innertube.buildPlayerRequestPayload
import com.nikhil.yt.innertube.YouTubeFailureClassifier
import com.nikhil.yt.innertube.YouTubeFailureKind
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
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64

/**
 * Independent guest player routes, no watch page or JavaScript bootstrap.
 * Only complete direct URLs or validated HLS are accepted. Cipher/n/SABR
 * responses go to the maintained parser instead of becoming broken MP4.
 * This client/pool/session exists only in :capsule_video; AUDIO is never used.
 */
object CapsuleDirectVideoExtractor {
    private const val BUDGET_MS = 5_000L
    private val nonceRandom = SecureRandom()
    private val visitors = CapsuleVideoGuestSessions()

    /** The reset generation crosses IPC with the next request, so a queued
     * reset cannot race behind that request and leave its old visitor in use. */
    @Synchronized
    fun beginSession(key: String) {
        visitors.beginSession(key)
    }
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Synchronized
    fun resolve(videoId: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean): CapsuleNewPipeResolved? {
        return resolvePlayer(videoId, quality, muxedOnly, YouTubeClient.VISIONOS)
    }

    @Synchronized
    fun resolveVr(videoId: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean): CapsuleNewPipeResolved? =
        resolvePlayer(videoId, quality, muxedOnly, YouTubeClient.ANDROID_VR_1_65_10)

    private fun resolvePlayer(videoId: String, quality: CapsuleNewPipeQuality, muxedOnly: Boolean,
                              identity: YouTubeClient): CapsuleNewPipeResolved? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        val base = buildPlayerRequestPayload(
            client = identity, locale = YouTubeLocale("US", "en"),
            visitorData = null, dataSyncId = null, videoId = videoId,
            playlistId = null, signatureTimestamp = null, poToken = null,
        )
        val context = base["context"]!!.jsonObject
        val clientContext = JsonObject(context["client"]!!.jsonObject.toMutableMap().apply {
            put("clientScreen", JsonPrimitive("WATCH")); put("platform", JsonPrimitive("MOBILE"))
            put("utcOffsetMinutes", JsonPrimitive(0))
        })
        val anonymousContext = JsonObject(context.toMutableMap().apply { put("client", clientContext) })
        // VR's anonymous player accepts its client context directly. Do not
        // add a visitor bootstrap round trip to the indexed route.
        val visitor = if (identity == YouTubeClient.ANDROID_VR_1_65_10) null else
            guestVisitor(identity.clientId, identity.clientVersion, identity.userAgent, anonymousContext, deadline)
        val payload = JsonObject(base.toMutableMap().apply {
            put("context", JsonObject(anonymousContext.toMutableMap().apply {
                put("client", JsonObject(clientContext.toMutableMap().apply { visitor?.let { put("visitorData", JsonPrimitive(it)) } }))
            }))
            put("cpn", JsonPrimitive(nonce(12)))
        })
        val response = post("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=${nonce(9)}&id=$videoId",
            payload, deadline, clientId = identity.clientId, clientVersion = identity.clientVersion, userAgent = identity.userAgent)
        return requireDirectVideo(response, videoId, quality, muxedOnly,
            onRejectedSession = { visitors.remove(identity.clientId) }).copy(userAgent = identity.userAgent)
    }

    /**
     * Android Reel is a different endpoint/client from VISIONOS and NewPipe's
     * parser. Accept only a complete, exact-ID muxed clip. Request shape based
     * on GPL-3.0 PipePipeExtractor 586325e33ed374ffddd62364dd20c26a999fb107,
     * YoutubeStreamExtractor.fetchAndroidReelMuxedFormats (InfinityLoop1308).
     */
    @Synchronized
    fun resolveReel(videoId: String, quality: CapsuleNewPipeQuality): CapsuleNewPipeResolved? {
        val version = "21.03.36"
        val userAgent = "com.google.android.youtube/$version (Linux; U; Android 15; US) gzip"
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        val anonymousContext = buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "ANDROID"); put("clientVersion", version)
                put("hl", "en"); put("gl", "US"); put("clientScreen", "WATCH")
                put("platform", "MOBILE"); put("osName", "Android"); put("osVersion", "16")
                put("androidSdkVersion", 36); put("utcOffsetMinutes", 0)
            })
        }
        val visitor = guestVisitor("3", version, userAgent, anonymousContext, deadline)
        val payload = buildJsonObject {
            put("context", JsonObject(anonymousContext.toMutableMap().apply {
                put("client", JsonObject(anonymousContext["client"]!!.jsonObject.toMutableMap().apply {
                    put("visitorData", JsonPrimitive(visitor))
                }))
            }))
            put("playerRequest", buildJsonObject {
                put("videoId", videoId); put("cpn", nonce(12))
                put("contentCheckOk", true); put("racyCheckOk", true)
            })
            put("disablePlayerResponse", false)
        }
        val response = post("https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch?prettyPrint=false&t=${nonce(9)}&id=$videoId&\$fields=playerResponse",
            payload, deadline, clientId = "3", clientVersion = version, userAgent = userAgent)
        val player = response["playerResponse"] as? JsonObject
            ?: throw CapsuleDirectVideoException(CapsuleNewPipeFailure.EXTRACTOR, "MISSING_PLAYER_RESPONSE")
        // Keep Reel's stronger exact-ID requirement; it can return a substitute.
        if ((player["playabilityStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "OK" &&
            (player["videoDetails"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull != videoId) {
            throw CapsuleDirectVideoException(CapsuleNewPipeFailure.EXTRACTOR, "VIDEO_ID_MISMATCH", penalizeRoute = false)
        }
        return requireDirectVideo(player, videoId, quality, muxedOnly = true,
            onRejectedSession = { visitors.remove("3") }).copy(userAgent = userAgent)
    }

    private fun guestVisitor(clientId: String, version: String, userAgent: String,
                             context: JsonObject, deadline: Long): String {
        visitors[clientId]?.let { return it }
        val host = if (clientId == "3") "youtubei.googleapis.com" else "www.youtube.com"
        val bootstrap = post("https://$host/youtubei/v1/visitor_id?prettyPrint=false",
            buildJsonObject { put("context", context) }, deadline, maxCallMs = 2_000L,
            clientId = clientId, clientVersion = version, userAgent = userAgent)
        val visitor = (bootstrap["responseContext"] as? JsonObject)?.get("visitorData")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: throw IOException("VIDEO anonymous visitor unavailable")
        visitors.put(clientId, visitor)
        return visitor
    }

    private fun nonce(bytes: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes).also(nonceRandom::nextBytes))

    private fun post(url: String, payload: JsonObject, deadline: Long, maxCallMs: Long = BUDGET_MS,
                     clientId: String = YouTubeClient.VISIONOS.clientId,
                     clientVersion: String = YouTubeClient.VISIONOS.clientVersion,
                     userAgent: String = YouTubeClient.VISIONOS.userAgent): JsonObject {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs <= 0L) throw SocketTimeoutException("VIDEO direct request budget elapsed")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(minOf(remainingMs, maxCallMs), TimeUnit.MILLISECONDS)
        return VideoExtractionRequests.execute(call) {
            call.execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("VIDEO HTTP 429", request.url.toString())
                if (!response.isSuccessful) throw IOException("VIDEO direct player HTTP ${response.code}")
                Json.parseToJsonElement(response.body.string()).jsonObject
            }
        }
    }
}

internal class CapsuleVideoGuestSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Visitor(val value: String, val untilMs: Long)
    private val visitors = mutableMapOf<String, Visitor>()
    private var sessionKey: String? = null

    fun beginSession(key: String) {
        if (sessionKey == key) return
        visitors.clear()
        sessionKey = key
    }

    operator fun get(clientId: String): String? = visitors[clientId]?.takeIf { it.untilMs > clock() }?.value
    fun put(clientId: String, value: String) { visitors[clientId] = Visitor(value, clock() + TimeUnit.HOURS.toMillis(6)) }
    fun remove(clientId: String) { visitors.remove(clientId) }
}

/** Only machine-generated summaries cross IPC. Never log player JSON, visitor
 * data, signed URLs, or arbitrary upstream exception messages. */
internal class CapsuleDirectVideoException(
    val failure: CapsuleNewPipeFailure,
    val diagnostic: String,
    val penalizeRoute: Boolean = true,
) : IOException("VIDEO direct $diagnostic")

internal fun requireDirectVideo(
    response: JsonObject, videoId: String, quality: CapsuleNewPipeQuality,
    muxedOnly: Boolean = false, nowMs: Long = System.currentTimeMillis(),
    onRejectedSession: () -> Unit = {},
): CapsuleNewPipeResolved {
    val playability = response["playabilityStatus"] as? JsonObject
    val status = (playability?.get("status") as? JsonPrimitive)?.contentOrNull
    if (status != "OK") {
        val kind = YouTubeFailureClassifier.classify(playabilityStatus = status, text = playability?.toString())
        val failure = when (kind) {
            YouTubeFailureKind.BOT_CHECK -> CapsuleNewPipeFailure.BOT_BLOCKED
            YouTubeFailureKind.RATE_LIMITED -> CapsuleNewPipeFailure.RATE_LIMITED
            YouTubeFailureKind.LOGIN_REQUIRED, YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureKind.UNPLAYABLE, YouTubeFailureKind.PERMANENT -> CapsuleNewPipeFailure.UNAVAILABLE
            YouTubeFailureKind.TRANSIENT -> CapsuleNewPipeFailure.NETWORK
            else -> CapsuleNewPipeFailure.EXTRACTOR
        }
        if (kind == YouTubeFailureKind.BOT_CHECK || kind == YouTubeFailureKind.LOGIN_REQUIRED) onRejectedSession()
        val safeStatus = status?.takeIf { it.matches(Regex("[A-Z_]{1,40}")) } ?: "UNKNOWN"
        throw CapsuleDirectVideoException(failure, "PLAYER_$safeStatus:$kind")
    }
    selectDirectVideo(response, videoId, quality, muxedOnly, nowMs)?.let { return it }
    val returnedId = ((response["videoDetails"] as? JsonObject)?.get("videoId") as? JsonPrimitive)?.contentOrNull
    val data = response["streamingData"] as? JsonObject
    val reason = when {
        returnedId != null && returnedId != videoId -> "VIDEO_ID_MISMATCH"
        data == null -> "NO_STREAMING_DATA"
        else -> "NO_COMPATIBLE_STREAM"
    }
    val formats = listOf("formats", "adaptiveFormats").flatMap { name ->
        (data?.get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }
    val cipher = formats.count { it["signatureCipher"] != null || it["cipher"] != null }
    val challenged = formats.count { format ->
        val url = (format["url"] as? JsonPrimitive)?.contentOrNull
        runCatching { URI(url).rawQuery.orEmpty().split('&').any { it.substringBefore('=') == "n" } }.getOrDefault(false)
    }
    throw CapsuleDirectVideoException(CapsuleNewPipeFailure.EXTRACTOR,
        "$reason:formats=${formats.size},cipher=$cipher,n=$challenged,hls=${data?.get("hlsManifestUrl") != null},muxed=$muxedOnly",
        penalizeRoute = false)
}

internal fun selectAndroidReelVideo(
    response: JsonObject, videoId: String, quality: CapsuleNewPipeQuality,
    nowMs: Long = System.currentTimeMillis(),
): CapsuleNewPipeResolved? {
    val player = response["playerResponse"] as? JsonObject ?: return null
    // Reel responses can be substitutions; missing IDs are not trustworthy.
    if ((player["videoDetails"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull != videoId) return null
    return selectDirectVideo(player, videoId, quality, muxedOnly = true, nowMs = nowMs)
}

internal fun selectDirectVideo(
    response: JsonObject, videoId: String, quality: CapsuleNewPipeQuality,
    muxedOnly: Boolean = false, nowMs: Long = System.currentTimeMillis(),
): CapsuleNewPipeResolved? {
    if ((response["playabilityStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.content != "OK") return null
    val returnedId = (response["videoDetails"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull
    if (returnedId != null && returnedId != videoId) return null
    val data = response["streamingData"] as? JsonObject ?: return null
    val manifest = if (!muxedOnly) selectDirectHlsManifest(
        (data["hlsManifestUrl"] as? JsonPrimitive)?.contentOrNull, videoId, quality, nowMs) else null
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
        .thenBy { it.number("fps") ?: 30 }.thenBy { it.number("bitrate") ?: 0 })
    val muxed = choose(formats("formats").filter { (it.number("audioChannels") ?: 0) > 0 || it.string("audioQuality") != null })
    val adaptive = formats("adaptiveFormats")
    val audio = adaptive.filter { it.string("mimeType")?.startsWith("audio/mp4") == true }
        .maxByOrNull { it.number("bitrate") ?: 0 }
    if (!muxedOnly) {
        fun track(f: JsonObject): CapsuleDashTrack? {
            val init = f["initRange"] as? JsonObject ?: return null
            val index = f["indexRange"] as? JsonObject ?: return null
            fun range(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.longOrNull ?: -1L
            val mime = f.string("mimeType").orEmpty()
            return CapsuleDashTrack(f.string("url")!!, f.number("itag") ?: -1, mime.substringBefore(';'),
                Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty(), f.number("bitrate") ?: 0,
                range(init, "start"), range(init, "end"), range(index, "start"), range(index, "end"),
                f.number("width") ?: 0, f.number("height") ?: 0, f.number("fps") ?: 30)
        }
        val tracks = adaptive.mapNotNull(::track)
        val duration = ((response["videoDetails"] as? JsonObject)?.get("lengthSeconds") as? JsonPrimitive)?.longOrNull?.times(1000L)
            ?: adaptive.mapNotNull { (it["approxDurationMs"] as? JsonPrimitive)?.longOrNull }.maxOrNull() ?: 0L
        indexedDashVideo(videoId, duration, tracks, tracks, quality,
            tracks.minOfOrNull { signedVideoExpiryMs(it.url, nowMs) } ?: nowMs)?.let { return it }
        if (manifest != null) return manifest
    }
    // Preserve the selected quality, including AUTO. A smaller muxed stream is
    // only a fallback when indexed/segmented or higher-quality tracks are absent.
    val adaptiveVideo = if (!muxedOnly && audio != null) choose(adaptive) else null
    val video = if (adaptiveVideo != null && (adaptiveVideo.number("height") ?: 0) > (muxed?.number("height") ?: 0))
        adaptiveVideo else muxed ?: adaptiveVideo ?: return null
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

/** HLS URLs encode expiry in their path, unlike progressive CDN URLs. */
internal fun selectDirectHlsManifest(
    url: String?, videoId: String, quality: CapsuleNewPipeQuality,
    nowMs: Long = System.currentTimeMillis(),
): CapsuleNewPipeResolved? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null ||
        uri.host?.endsWith(".googlevideo.com", ignoreCase = true) != true ||
        !uri.path.orEmpty().startsWith("/api/manifest/hls_")) return null
    val query = uri.rawQuery.orEmpty().split('&').associate {
        it.substringBefore('=') to it.substringAfter('=', "")
    }
    if (query.containsKey("n") || query.containsKey("range")) return null
    val expires = query["expire"]?.toLongOrNull()
        ?: Regex("/expire/(\\d+)(?:/|$)").find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        ?: return null
    if (expires <= nowMs / 1000L + 45L || expires > Long.MAX_VALUE / 1000L) return null
    return CapsuleNewPipeResolved(videoId, url!!, null,
        if (quality == CapsuleNewPipeQuality.AUTO) "AUTO" else "≤${quality.maxHeight}p",
        0, 0, -1, -1, expires * 1000L,
        delivery = CapsuleVideoDelivery.HLS, maxHeight = quality.maxHeight)
}
