/*
 * Capsule MUSIC — first-party YouTube Music audio/video counterpart.
 * Decode only the identity fields required for the official song/video toggle.
 * The entire playlist panel has more mandatory fields that are not guaranteed
 * on transient /next counterpart rows. GPL-3.0
 */
package com.nikhil.yt.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelVideoWrapperRenderer(
    val primaryRenderer: VideoPart? = null,
    val counterpart: List<Counterpart>? = null,
) {
    @Serializable
    data class VideoPart(
        val playlistPanelVideoRenderer: VideoIdentity? = null,
    )

    @Serializable
    data class VideoIdentity(
        val videoId: String? = null,
        val navigationEndpoint: NavigationEndpoint? = null,
    )

    @Serializable
    data class Counterpart(
        val counterpartRenderer: VideoPart? = null,
    )
}
