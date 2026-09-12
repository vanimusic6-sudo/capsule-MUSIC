/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.models.body

import com.nikhil.yt.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlayerBody(
    val context: Context,
    val videoId: String,
    val playlistId: String?,
    val playbackContext: PlaybackContext? = null,
    val serviceIntegrityDimensions: ServiceIntegrityDimensions? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
) {
    @Serializable
    data class PlaybackContext(
        val contentPlaybackContext: ContentPlaybackContext,
    ) {
        @Serializable
        data class ContentPlaybackContext(
            /*
             * YouTube's HTML5 identities expect this even when no signature
             * timestamp is needed. Omitting the whole playback context makes
             * Web Embedded and TV clients answer with the generic
             * "reload the page" playability error.
             */
            val html5Preference: String = "HTML5_PREF_WANTS",
            val signatureTimestamp: Int? = null,
            val encryptedHostFlags: String? = null,
        )
    }

    @Serializable
    data class ServiceIntegrityDimensions(
        val poToken: String
    )
}
