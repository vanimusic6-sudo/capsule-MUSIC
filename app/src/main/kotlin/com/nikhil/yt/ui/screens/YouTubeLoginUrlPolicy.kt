package com.nikhil.yt.ui.screens

import java.net.URI

internal fun isYouTubeMusicLoginPage(url: String?): Boolean {
    val parsed =
        runCatching { URI(url ?: return false) }
            .getOrNull()
            ?: return false

    return parsed.scheme.equals("https", ignoreCase = true) &&
        parsed.host.equals("music.youtube.com", ignoreCase = true)
}
