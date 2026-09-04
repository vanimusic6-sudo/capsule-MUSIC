/*
 * Capsule MUSIC
 *
 * Legacy playback extraction was removed. This object remains only as a tiny
 * source-compatibility shim for the old background loudness call in
 * MusicService. It performs no YouTube player request, no client selection,
 * no signature deciphering and no stream URL resolution.
 *
 * GPL-3.0
 */
package com.nikhil.yt.utils

import timber.log.Timber

@Deprecated(
    message = "Playback extraction is owned by CapsuleAudioEngine/InnerTubeX",
    level = DeprecationLevel.WARNING,
)
object YTPlayerUtils {
    data class LoudnessMetadata(
        val loudnessDb: Double?,
        val perceptualLoudnessDb: Double?,
    )

    /**
     * Intentionally does not start a second player request merely to obtain
     * normalization metadata. MusicService falls back to unity gain when the
     * actual playback response/database has no loudness information.
     */
    suspend fun resolveLoudnessForNormalization(videoId: String): LoudnessMetadata? {
        Timber.tag("AudioNormalization").d(
            "No secondary loudness request for %s; using playback metadata only",
            videoId,
        )
        return null
    }
}
