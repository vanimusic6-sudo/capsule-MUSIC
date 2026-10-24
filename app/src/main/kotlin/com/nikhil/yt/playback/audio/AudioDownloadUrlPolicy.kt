package com.nikhil.yt.playback.audio

import java.io.IOException
import java.net.URI

/** Validate server-provided addresses before handing them to the offline transport. */
internal object AudioDownloadUrlPolicy {
    fun requireHttps(address: String): URI {
        val uri = try { URI(address) } catch (_: Exception) { throw IOException("Invalid media address") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null
        ) throw IOException("Media address must be HTTPS without credentials or fragments")
        return uri
    }

    fun requireYouTubeMedia(address: String) {
        val host = requireHttps(address).host.lowercase(java.util.Locale.US)
        if (listOf("googlevideo.com", "youtube.com", "youtube-nocookie.com", "googleusercontent.com")
                .none { host == it || host.endsWith(".$it") }) {
            throw IOException("Unexpected YouTube audio host")
        }
    }
}
