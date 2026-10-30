/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Album
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.BrowseEndpoint
import com.nikhil.yt.innertube.models.MusicCarouselShelfRenderer
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.MusicShelfRenderer
import com.nikhil.yt.innertube.models.MusicTwoRowItemRenderer
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SectionListRenderer
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.getItems
import com.nikhil.yt.innertube.models.oddElements
import com.nikhil.yt.innertube.models.splitBySeparator

data class ArtistSection(
    val title: String,
    val items: List<YTItem>,
    val moreEndpoint: BrowseEndpoint?,
)

data class ArtistPage(
    val artist: ArtistItem,
    val sections: List<ArtistSection>,
    val description: String?,
) {
    companion object {
        fun fromSectionListRendererContent(content: SectionListRenderer.Content): ArtistSection? {
            return when {
                content.musicShelfRenderer != null -> fromMusicShelfRenderer(content.musicShelfRenderer)
                content.musicCarouselShelfRenderer != null -> fromMusicCarouselShelfRenderer(content.musicCarouselShelfRenderer)
                else -> null
            }
        }

        private fun fromMusicShelfRenderer(renderer: MusicShelfRenderer): ArtistSection? {
            return ArtistSection(
                title = renderer.title?.runs?.firstOrNull()?.text ?: "",
                items = renderer.contents?.getItems()?.mapNotNull {
                    fromMusicResponsiveListItemRenderer(it)
                        ?: ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                }?.ifEmpty { null } ?: return null,
                moreEndpoint = renderer.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint
                    ?: renderer.title?.runs?.firstNotNullOfOrNull { it.navigationEndpoint?.browseEndpoint }
            )
        }

        private fun fromMusicCarouselShelfRenderer(renderer: MusicCarouselShelfRenderer): ArtistSection? {
            return ArtistSection(
                title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text ?: return null,
                items = renderer.contents.mapNotNull {
                    it.musicTwoRowItemRenderer?.let(::fromMusicTwoRowItemRenderer)
                        ?: it.musicResponsiveListItemRenderer?.let(
                            ArtistItemsPage::fromMusicResponsiveListItemRenderer
                        )
                }.ifEmpty { null } ?: return null,
                moreEndpoint = renderer.header.musicCarouselShelfBasicHeaderRenderer.let { header ->
                    header.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint
                        ?: header.moreContentButton?.buttonRenderer?.command?.browseEndpoint
                        ?: header.title.runs?.firstNotNullOfOrNull { it.navigationEndpoint?.browseEndpoint }
                }
            )
        }

        private fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            return SongItem(
                id = renderer.playlistItemData?.videoId
                    ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.text ?: return null,
                artists = PageHelper.extractSongArtists(
                    renderer.flexColumns
                        .getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                ),
                album = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ALBUM").ifEmpty { renderer.flexColumns.getOrNull(3)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs }?.firstOrNull()?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                    )
                },
                duration = null,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint
            )
        }

        private fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
            return when {
                renderer.isSong -> {
                    SongItem(
                        id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = PageHelper.extractSongArtists(renderer.subtitle?.runs),
                        album = null,
                        duration = null,
                        endpoint = renderer.navigationEndpoint.watchEndpoint,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
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
                }

                renderer.isPlaylist -> {
                    // Playlist from YouTube Music
                    PlaylistItem(
                        id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        author = Artist(
                            name = renderer.subtitle?.runs?.lastOrNull()?.text.orEmpty(),
                            id = null
                        ),
                        songCountText = null,
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
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
                        channelId = renderer.menu?.menuRenderer?.items?.find {
                            it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE"
                        }?.toggleMenuServiceItemRenderer?.defaultServiceEndpoint?.subscribeEndpoint?.channelIds?.firstOrNull(),
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }

                else -> null
            }
        }
    }
}
