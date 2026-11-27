/* Capsule MUSIC — PipePipe audio-only output contract. GPL-3.0. */
package com.nikhil.yt.innertube.video

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

enum class PipeAudioQuality { HIGH, LOW, AAC }
class PipeAudioAccessRestrictedException : IOException("AUDIO age restriction")

/** Metadata only. No account data or decoder state. Validate again in the consumer process. */
data class PipeAudioStream(
    val id: String,
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: String,
    val bitrate: Int,
    val length: Long,
    val sampleRate: Int?,
    val durationMs: Long?,
    val expiresAtMs: Long,
) {
    fun valid(expectedId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (id != expectedId || !id.matches(Regex("[A-Za-z0-9_-]{11}")) ||
            !VideoStreamValidation.isSupportedUrl(url) || !VideoStreamValidation.isFresh(expiresAtMs, nowMs)) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.queryParameter("itag")?.toIntOrNull() != itag ||
            parsed.queryParameter("range") != null ||
            parsed.queryParameter("expire")?.toLongOrNull()?.let { it in 1..Long.MAX_VALUE / 1000L && it * 1000L == expiresAtMs } != true) return false
        val signedLength = parsed.queryParameter("clen")?.toLongOrNull()
        if (signedLength != null && signedLength != length) return false
        if (length !in 1L..8_589_934_592L || bitrate !in 1..1_500_000 ||
            (sampleRate != null && sampleRate !in 8_000..192_000) ||
            (durationMs != null && durationMs !in 1L..604_800_000L)) return false
        return when (itag) {
            249, 250, 251 -> mimeType == "audio/webm" && codec == "opus"
            139, 140, 141 -> mimeType == "audio/mp4" && codec.matches(Regex("mp4a\\.40\\.[0-9]{1,2}"))
            else -> false
        }
    }

    companion object {
        internal fun fromMetadata(id: String, rawUrl: String, itag: Int, mime: String, codec: String,
            bitrate: Int, length: Long?, rate: Int?, duration: Long?): PipeAudioStream? {
            if (!VideoStreamValidation.isSupportedUrl(rawUrl)) return null
            val url = rawUrl.toHttpUrlOrNull() ?: return null
            val expiry = url.queryParameter("expire")?.toLongOrNull()
                ?.takeIf { it in 1..Long.MAX_VALUE / 1000L } ?: return null
            val knownLength = length?.takeIf { it > 0 } ?: url.queryParameter("clen")?.toLongOrNull() ?: return null
            // PipePipe's WebM MediaFormat may use audio/webm or audio/opus. The
            // signed YouTube itag and explicit Opus codec identify the container.
            val normalizedMime = if (itag in 249..251 && mime == "audio/opus" && codec == "opus") "audio/webm" else mime
            return PipeAudioStream(id, rawUrl, itag, normalizedMime, codec, bitrate, knownLength,
                rate?.takeIf { it > 0 }, duration?.takeIf { it > 0 }, expiry * 1000L)
                .takeIf { it.valid(id) }
        }
    }
}

internal fun selectPipeAudio(candidates: List<PipeAudioStream>, quality: PipeAudioQuality): PipeAudioStream? {
    val eligible = if (quality == PipeAudioQuality.AAC) candidates.filter { it.mimeType == "audio/mp4" } else candidates
    return if (quality == PipeAudioQuality.LOW) eligible.minByOrNull { it.bitrate }
    else eligible.maxByOrNull { it.bitrate }
}

/** Called only from the private extractor provider. Request ownership/cancellation remains shared. */
object CapsulePipeAudio {
    const val BACKEND = "PIPEPIPE_AUDIO"
    val userAgent: String get() = CapsulePipePipeDownloader.USER_AGENT
    fun resolve(id: String, quality: PipeAudioQuality): PipeAudioStream? =
        CapsulePipePipeExtractor.resolveAudio(id, quality)
}
