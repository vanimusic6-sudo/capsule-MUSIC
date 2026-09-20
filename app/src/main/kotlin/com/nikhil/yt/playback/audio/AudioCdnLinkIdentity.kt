package com.nikhil.yt.playback.audio

import okhttp3.HttpUrl
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

/** The original link for one CDN request; redirect/shortcut requests keep this tag. */
internal data class AudioCdnRequestTrace(
    val originalUrl: HttpUrl,
    val linkRef: String,
)
