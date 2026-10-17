package com.nikhil.yt.constants

/** Only fallback values for absent DataStore keys; no existing value is rewritten. */
object ReleasePlaybackDefaults {
    val audioQuality = AudioQuality.HIGH
    const val metered = true
    const val persistentQueue = true
    const val normalization = false
    const val autoSkipOnError = true
    const val pauseOnMute = true
    // A selected audio format/output route is needed to confirm Media3 offload.
    const val audioOffloadUntilVerified = false
}
