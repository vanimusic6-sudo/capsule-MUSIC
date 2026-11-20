package com.nikhil.yt.playback.audio

/** Developer-enforced SABR and bounded automatic fallback are different modes. */
internal object CapsuleSabrRoutePolicy {
    const val PROFILE = "WEB_REMIX_SABR"
    private const val DIRECT_ATTEMPTS_BEFORE_SABR = 2

    fun select(
        forceSabr: Boolean,
        download: Boolean,
        priority: AudioResolvePriority,
        botSignalAlreadySeen: Boolean,
        attempted: Set<String>,
    ): String? {
        if (download || PROFILE in attempted) return null
        if (forceSabr) return PROFILE
        return PROFILE.takeIf {
            priority == AudioResolvePriority.PLAYBACK &&
                !botSignalAlreadySeen &&
                attempted.size >= DIRECT_ATTEMPTS_BEFORE_SABR
        }
    }
}
