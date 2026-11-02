/*
 * Capsule Music VIDEO native SABR transport contract.
 * GPL-3.0
 */
package com.nikhil.yt.innertube.video

import java.util.Base64

/**
 * Signed YouTube SABR session metadata. Only a verified HTTPS GoogleVideo
 * endpoint, bounded ustreamer config and two pinned MP4 itags may cross IPC.
 * No account cookies, authorization headers, or arbitrary endpoint hosts.
 * The actual UMP media is fetched ONLY inside the VIDEO Media3 DataSource.
 */
data class CapsuleSabrVideoSpec(
    val ustreamerConfigBase64: String,
    val videoItag: Int,
    val videoLastModified: Long,
    val videoLength: Long,
    val audioItag: Int,
    val audioLastModified: Long,
    val audioLength: Long,
    val clientName: Int,
    val clientVersion: String,
    val durationMs: Long,
    val poTokenBase64: String = "",
    val videoXtags: String? = null,
    val audioXtags: String? = null,
    val requestOrigin: String? = null,
) {
    fun isValid(): Boolean {
        if (videoItag !in 1..65535 || audioItag !in 1..65535 || videoItag == audioItag ||
            videoLength !in 1L..4_000_000_000L || audioLength !in 1L..1_000_000_000L ||
            videoLastModified < 0 || audioLastModified < 0 ||
            clientName !in 1..1000 || clientVersion.length !in 1..48 ||
            clientVersion.any { !(it.isDigit() || it == '.') } ||
            durationMs !in 1L..86_400_000L ||
            ustreamerConfigBase64.length !in 4..65_536 || poTokenBase64.length > 8192
        ) return false
        if (listOfNotNull(videoXtags, audioXtags).any { tag ->
                tag.length > 1024 || tag.any { !(it.isLetterOrDigit() || it in "-_=+") }
            } || requestOrigin !in setOf(null, "https://music.youtube.com", "https://www.youtube.com", "https://m.youtube.com")) return false
        return runCatching {
            val config = decodeBase64(ustreamerConfigBase64)
            val token = if (poTokenBase64.isBlank()) ByteArray(0) else decodeBase64(poTokenBase64)
            config.size in 4..48_000 && token.size <= 6144
        }.getOrDefault(false)
    }

    companion object {
        fun decodeBase64(raw: String): ByteArray = runCatching {
            Base64.getUrlDecoder().decode(raw.trim())
        }.getOrElse { Base64.getDecoder().decode(raw.trim()) }
    }
}
