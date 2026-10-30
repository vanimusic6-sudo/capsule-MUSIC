/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.models

import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_AUDIOBOOK
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import kotlinx.serialization.Serializable

/**
 * Two row: a big thumbnail, a title, and a subtitle
 * Used in [GridRenderer] and [MusicCarouselShelfRenderer]
 * Item type: song, video, album, playlist, artist
 */
@Serializable
data class MusicTwoRowItemRenderer(
    val title: Runs,
    val subtitle: Runs?,
    val subtitleBadges: List<Badges>?,
    val menu: Menu?,
    val thumbnailRenderer: ThumbnailRenderer,
    val navigationEndpoint: NavigationEndpoint,
    val thumbnailOverlay: MusicResponsiveListItemRenderer.Overlay?,
) {
    val isSong: Boolean
        get() = navigationEndpoint.endpoint is WatchEndpoint
    // Some official artist carousel/grid entries provide only a browseId, without
    // browseEndpointContextSupportedConfigs. Dropping those silently was hiding
    // albums and playlist releases even though they were valid navigable entries.
    val isPlaylist: Boolean
        get() {
            val browse = navigationEndpoint.browseEndpoint ?: return false
            val pageType = browse.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig?.pageType
            return pageType == MUSIC_PAGE_TYPE_PLAYLIST ||
                (pageType == null && browse.browseId.startsWith("VL"))
        }
    val isAlbum: Boolean
        get() {
            val browse = navigationEndpoint.browseEndpoint ?: return false
            val pageType = browse.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig?.pageType
            return pageType == MUSIC_PAGE_TYPE_ALBUM ||
                pageType == MUSIC_PAGE_TYPE_AUDIOBOOK ||
                (pageType == null && browse.browseId.startsWith("MPRE"))
        }
    val isArtist: Boolean
        get() =
            navigationEndpoint.browseEndpoint
                ?.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig
                ?.pageType ==
                MUSIC_PAGE_TYPE_ARTIST
}
