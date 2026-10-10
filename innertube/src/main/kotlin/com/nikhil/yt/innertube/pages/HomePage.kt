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
import com.nikhil.yt.innertube.models.MusicTwoRowItemRenderer
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.oddElements
import com.nikhil.yt.innertube.models.splitBySeparator
import com.nikhil.yt.innertube.models.filterExplicit

data class HomePage(
    val sections: List<Section>,
    val continuation: String? = null,
) {
    data class Section(
        val title: String,
        val label: String?,
        val thumbnail: String?,
        val endpoint: BrowseEndpoint?,
        val items: List<YTItem>,
    ) {
        companion object {
            fun fromMusicCarouselShelfRenderer(renderer: MusicCarouselShelfRenderer): Section? {
                return Section(
                    title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text ?: return null,
                    label = renderer.header.musicCarouselShelfBasicHeaderRenderer.strapline?.runs?.firstOrNull()?.text,
                    thumbnail = renderer.header.musicCarouselShelfBasicHeaderRenderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                    endpoint = renderer.header.musicCarouselShelfBasicHeaderRenderer.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint,
                    // MetroList also parses the responsive items returned by YouTube
                    // for Quick Picks and some filtered Home carousels. Previously Capsule
                    // silently discarded every such section as empty.
                    items = renderer.contents.mapNotNull { content ->
                        content.musicTwoRowItemRenderer?.let(::fromMusicTwoRowItemRenderer)
                            ?: content.musicResponsiveListItemRenderer?.let(::fromMusicResponsiveListItemRenderer)
                    }.ifEmpty { return null }
                )
            }

            private fun fromMusicResponsiveListItemRenderer(
                renderer: MusicResponsiveListItemRenderer,
            ): SongItem? {
                if (!renderer.isSong) return null
                val videoId =
                    renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.playlistItemData?.videoId
                        ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                            ?.content?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint?.watchEndpoint?.videoId
                        ?: return null
                val title =
                    renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text
                        ?.runs?.firstOrNull()?.text ?: return null
                val artists =
                    renderer.flexColumns.getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                        ?.splitBySeparator()?.firstOrNull()
                        ?.let(PageHelper::extractArtists).orEmpty()
                val thumbnail =
                    renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: renderer.thumbnail?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
                        ?: return null

                return SongItem(
                    id = videoId,
                    title = title,
                    artists = artists,
                    thumbnail = thumbnail,
                    explicit = renderer.badges?.any {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } == true,
                    endpoint = renderer.navigationEndpoint?.watchEndpoint,
                )
            }

            private fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
                return when {
                    renderer.isSong -> {
                        val subtitleRuns = renderer.subtitle?.runs ?: return null
                        val subtitleGroups = subtitleRuns.splitBySeparator()
                        val artistRuns = subtitleGroups.firstOrNull()
                        val artists = PageHelper.extractArtists(artistRuns)
                        SongItem(
                            id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            artists = artists,
                            album = subtitleRuns.firstOrNull { run ->
                                run.navigationEndpoint?.browseEndpoint?.browseId?.startsWith("MPREb_") == true
                            }?.let { run ->
                                val endpoint = run.navigationEndpoint?.browseEndpoint ?: return null
                                Album(
                                    name = run.text,
                                    id = endpoint.browseId
                                )
                            },
                            duration = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit = renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true
                        )
                    }
                    renderer.isAlbum -> {
                        AlbumItem(
                            browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint?.playlistId ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            artists = renderer.subtitle?.runs?.oddElements()?.drop(1)?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                                )
                            },
                            year = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit = renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null
                        )
                    }

                    renderer.isPlaylist -> {
                        PlaylistItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                            author = Artist(
                                name = renderer.subtitle?.runs?.lastOrNull()?.text ?: return null,
                                id = null
                            ),
                            songCountText = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            playEndpoint = renderer.thumbnailOverlay
                                ?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                            shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                            radioEndpoint = renderer.menu.menuRenderer.items.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                        )
                    }

                    renderer.isArtist -> {
                        ArtistItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                            radioEndpoint = renderer.menu.menuRenderer.items.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        )
                    }

                    else -> null
                }
            }
        }
    }

    fun filterExplicit(enabled: Boolean = true) =
        if (enabled) {
            copy(sections = sections.map {
                it.copy(items = it.items.filterExplicit())
            })
        } else this

}
