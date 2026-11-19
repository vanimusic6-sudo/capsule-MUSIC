/*
 * Capsule Music — first-party YouTube Music audio/video counterpart.
 * Shape follows the documented /next playlistPanelVideoWrapperRenderer
 * response; retain only fields needed to select the correct clip.
 * GPL-3.0
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
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer? = null,
    )

    @Serializable
    data class Counterpart(
        val counterpartRenderer: VideoPart? = null,
    )
}
