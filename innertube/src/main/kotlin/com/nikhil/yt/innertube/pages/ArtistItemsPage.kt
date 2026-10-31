/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Album
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.MusicTwoRowItemRenderer
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.oddElements
import com.nikhil.yt.innertube.models.splitBySeparator
import com.nikhil.yt.innertube.utils.parseTime

data class ArtistItemsPage(
    val title: String,
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        /** Responsive rows can represent releases too, not just playable songs.
         * YouTube Music uses this format for some artist albums and playlists.
         */
        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            val title = renderer.flexColumns.firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer?.text
                ?.runs?.firstOrNull()?.text ?: return null
            val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl().orEmpty()
            val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId
            return when {
                renderer.isAlbum && browseId != null -> AlbumItem(
                    browseId = browseId,
                    playlistId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint?.playlistId
                        ?: browseId,
                    title = title,
                    artists = null,
                    year = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer
                        ?.text?.runs?.lastOrNull()?.text?.toIntOrNull(),
                    thumbnail = thumbnail,
                    explicit = renderer.badges?.any {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } == true,
                )
                renderer.isPlaylist && browseId != null -> PlaylistItem(
                    id = browseId.removePrefix("VL"),
                    title = title,
                    author = null,
                    songCountText = null,
                    thumbnail = thumbnail,
                    playEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                )
                else -> SongItem(
                    id = renderer.playlistItemData?.videoId
                        ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: return null,
                    title = title,
                    artists = PageHelper.extractSongArtists(
                        renderer.flexColumns.getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                    ),
                    album = renderer.flexColumns.getOrNull(3)
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                                    ?: return@let null
                            )
                        },
                    duration = renderer.fixedColumns?.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                        ?.firstOrNull()?.text?.parseTime(),
                    thumbnail = thumbnail,
                    explicit = renderer.badges?.any {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } == true,
                    endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint
                        ?: renderer.navigationEndpoint?.watchEndpoint
                )
            }
        }

        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            return when {
                renderer.isAlbum -> AlbumItem(
                    browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                    playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer
                        ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.anyWatchEndpoint?.playlistId
                            ?: renderer.navigationEndpoint.browseEndpoint?.browseId.orEmpty(),
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = null,
                    year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                    explicit = renderer.subtitleBadges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )
                // Video
                renderer.isSong -> SongItem(
                    id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    artists = PageHelper.extractSongArtists(
                        renderer.subtitle?.runs
                    ),
                    album = null,
                    duration = null,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                    endpoint = renderer.navigationEndpoint.watchEndpoint
                )
                renderer.isPlaylist -> PlaylistItem(
                    id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                    title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                    author = renderer.subtitle?.runs?.getOrNull(2)?.let {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    },
                    songCountText = renderer.subtitle?.runs?.getOrNull(4)?.text,
                    thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                    playEndpoint = renderer.thumbnailOverlay
                        ?.musicItemThumbnailOverlayRenderer?.content
                        ?.musicPlayButtonRenderer?.playNavigationEndpoint
                        ?.watchPlaylistEndpoint,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )
                else -> null
            }
        }
    }
}
