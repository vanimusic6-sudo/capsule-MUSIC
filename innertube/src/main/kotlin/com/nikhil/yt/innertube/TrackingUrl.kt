package com.nikhil.yt.innertube

import java.net.URI

internal fun requireTrustedTrackingUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host in setOf("www.youtube.com", "music.youtube.com", "s.youtube.com") &&
        uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
        uri.path in setOf("/api/stats/playback", "/api/stats/watchtime")) {
        "Untrusted playback tracking endpoint"
    }
}
