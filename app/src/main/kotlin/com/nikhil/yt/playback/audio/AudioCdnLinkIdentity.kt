package com.nikhil.yt.playback.audio

import okhttp3.HttpUrl
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An ephemeral, non-reversible correlation ID for a signed CDN URL. It is stable only for this
 * process, never exposes cookies/PoTokens/signatures or the URL itself, and must not be persisted.
 * This lets field logs distinguish retrying one link from obtaining another link.
 */
internal object AudioCdnLinkIdentity {
    private val processSalt = ByteArray(32).also(SecureRandom()::nextBytes)
    private val hex = "0123456789abcdef".toCharArray()

    fun ref(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(processSalt)
        val hash = digest.digest(url.toByteArray(StandardCharsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) {
                val value = hash[i].toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}

/**
 * Correlation IDs exist only inside one process. They are not a request header and never go to
 * YouTube. Each CDN open receives a flow ID; each internal redirect/shortcut reissue has a hop.
 */
internal object AudioCdnTraceIds {
    private val counter = AtomicLong()
    fun next(): Long = counter.incrementAndGet()
}

/** Original signed link plus the current hop through the existing redirect policy. */
internal data class AudioCdnRequestTrace(
    val originalUrl: HttpUrl,
    val linkRef: String,
    val flowId: Long = 0L,
    val hop: Int = 0,
    val stage: String = "original",
)

/**
 * Fingerprint exactly the outgoing request's METHOD and HEADER NAMES/VALUES without exporting
 * the values. A fresh, secret in-memory salt prevents guessing small cookies/tokens from logs.
 * A changed fingerprint is evidence the request changed, NOT proof of why the server refused it.
 */
internal fun audioCdnHeaderRef(request: Request): String {
    val canonical = buildString {
        append(request.method).append('\\n')
        request.headers.names().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
            append(name.lowercase(Locale.ROOT)).append(':')
            request.headers.values(name).forEach { value ->
                append(value.length).append(':').append(value).append(';')
            }
            append('\\n')
        }
    }
    return AudioCdnLinkIdentity.ref("headers\\n" + canonical)
}

/** Never print a verbatim header value except an explicitly validated numerical byte range. */
internal fun audioCdnSafeRange(range: String?): String =
    range?.takeIf { it.length <= 45 && it.matches(Regex("bytes=[0-9]+-[0-9]*")) } ?: "none"

/** Only a query field's presence and a per-process salted reference may leave the phone. */
internal fun audioCdnQueryRef(url: HttpUrl, key: String): String =
    url.queryParameter(key)?.let { AudioCdnLinkIdentity.ref("query:" + key + ":" + it) } ?: "none"

