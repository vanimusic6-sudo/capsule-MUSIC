package com.nikhil.yt.playback.audio

/** SABR remains an explicit developer transport, never a production retry. */
internal object CapsuleSabrRoutePolicy {
    const val PROFILE = "WEB_REMIX_SABR"
    fun select(forceSabr: Boolean, download: Boolean, attempted: Set<String>): String? =
        PROFILE.takeIf { forceSabr && !download && it !in attempted }
}
