package com.nikhil.yt.playback.video

import android.net.Uri
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.selectBestVideoFormat
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import kotlinx.serialization.json.*

/** One bounded, in-memory inventory from the SAME successful WEB_REMIX response.
 * It never crosses IPC, survives an account change, or appears in diagnostics. */
internal class CapsuleWebVideoInventory private constructor(
    val videoId: String, private val formats: List<Format>,
) {
    fun select(maxHeight: Int): Format? = selectBestVideoFormat(
        formats.filter { (it.height ?: 0) in 1..maxHeight }, requireUrl = false, maxHeight = maxHeight,
    )

    override fun toString() = "CapsuleWebVideoInventory(redacted)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromPlayerResponse(root: JsonObject): CapsuleWebVideoInventory? = runCatching {
            val id = ((root["videoDetails"] as? JsonObject)?.get("videoId") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
            val streaming = root["streamingData"] as? JsonObject ?: return null
            val formats = listOf("adaptiveFormats", "formats").flatMap { key ->
                (streaming[key] as? JsonArray).orEmpty().take(64).mapNotNull { element ->
                    runCatching { json.decodeFromJsonElement<Format>(element) }.getOrNull()
                        ?.takeIf { it.itag > 0 && (it.width ?: 0) > 0 && (it.height ?: 0) > 0 &&
                            it.mimeType.startsWith("video/") &&
                            (!it.url.isNullOrBlank() || !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank()) }
                }
            }.distinctBy { it.itag }
            CapsuleWebVideoInventory(id, formats)
        }.getOrNull()
    }
}

/** Cipher processing belongs to the same VIDEO context and the same player JS.
 * Never reuse another itag's signature/n-transform or remove an unsolved n. */
internal suspend fun selectWebVideoQuality(
    base: ExtractedStream, inventory: CapsuleWebVideoInventory?, maxHeight: Int,
    process: suspend (Format) -> Format?,
): ExtractedStream {
    val candidate = inventory?.takeIf { it.videoId == base.videoId }?.select(maxHeight)
    if (candidate == null || candidate.itag == base.videoItag) {
        require((base.videoHeight ?: 0) in 1..maxHeight) { "VIDEO requested resolution unavailable" }
        return base
    }
    val solved = process(candidate) ?: error("VIDEO requested format could not be deciphered")
    require(solved.itag == candidate.itag && solved.height == candidate.height && solved.width == candidate.width)
    val raw = requireNotNull(solved.url)
    require(CapsuleVideoIpc.isSafeStreamUrl(raw)) { "VIDEO selected format URL invalid" }
    val selected = Uri.parse(raw)
    // A PO token and playback nonce are scoped to this exact clip/client session.
    val reference = Uri.parse(base.videoUrl ?: base.audioUrl)
    val url = selected.buildUpon().apply {
        for (key in listOf("pot", "cpn")) {
            if (selected.getQueryParameter(key).isNullOrBlank()) {
                reference.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { appendQueryParameter(key, it) }
            }
        }
    }.build().toString()
    val expiry = selected.getQueryParameter("expire")?.toLongOrNull()?.takeIf { it in 1..9_000_000_000L }
        ?.times(1000L)?.let { kotlin.time.Instant.fromEpochMilliseconds(it) }
    val expiresAt = listOfNotNull(base.expiresAt, expiry).minOrNull()
    return base.copy(
        videoUrl = url, videoItag = solved.itag, videoWidth = solved.width, videoHeight = solved.height,
        videoMimeType = solved.mimeType.substringBefore(';').trim(),
        videoCodecs = solved.mimeType.substringAfter("codecs=\"", "").substringBefore('"'),
        videoBitrate = solved.bitrate, videoContentLengthBytes = solved.contentLength,
        expiresAt = expiresAt,
    )
}
