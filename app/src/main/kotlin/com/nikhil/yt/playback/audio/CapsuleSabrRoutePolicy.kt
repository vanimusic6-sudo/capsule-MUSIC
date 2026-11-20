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
        previousDirectFailures: Int = 0,
    ): String? {
        // Never replay a previously failed SABR route during its per-track cooldown.
        // Direct CDN failures from prior Media3 reloads are as meaningful as
        // direct /player failures inside this one resolve.
        if (download || PROFILE in attempted) return null
        if (forceSabr) return PROFILE
        return PROFILE.takeIf {
            priority == AudioResolvePriority.PLAYBACK &&
                !botSignalAlreadySeen &&
                attempted.count { it != PROFILE } + previousDirectFailures >= DIRECT_ATTEMPTS_BEFORE_SABR
        }
    }
}
