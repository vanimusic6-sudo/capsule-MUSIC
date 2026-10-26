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
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.nikhil.yt.innertube.models.MusicCardShelfRenderer
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.Run
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import com.nikhil.yt.innertube.models.oddElements
import com.nikhil.yt.innertube.models.splitBySeparator
import com.nikhil.yt.innertube.utils.parseTime

private val searchDurationPattern = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")

// Compatibility wrapper for historical tests. All extraction now comes from
// the same canonical PageHelper routine used by playlists and album pages.
internal fun extractSearchSongArtists(runs: List<Run>): List<Artist> =
    PageHelper.extractSongArtists(runs)

internal fun extractSearchSongDuration(runs: List<Run>): Int? =
    runs.firstNotNullOfOrNull { run ->
        run.text.trim().takeIf(searchDurationPattern::matches)?.parseTime()
    }

internal fun extractSearchCardArtists(renderer: MusicCardShelfRenderer): List<Artist> {
    val browseEndpoint = renderer.onTap.browseEndpoint
    return if (browseEndpoint?.isArtistEndpoint == true) {
        renderer.title.runs?.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { listOf(Artist(name = it, id = browseEndpoint.browseId)) }.orEmpty()
    } else {
        extractSearchSongArtists(renderer.subtitle.runs.orEmpty())
    }
}

/**
 * A song in Top result may expose only the primary performer while its
 * duplicate in Songs carries all collaborators (or vice versa). Merge the
 * verified credits of the SAME video ID; never replace them with artists
 * returned by an unrelated artist search query.
 */
internal fun enrichSearchSummaryArtists(summaries: List<SearchSummary>): List<SearchSummary> {
    val knownArtists = summaries.flatMap { it.items }
        .filterIsInstance<SongItem>()
        .groupBy { it.id }
        .mapValues { (_, occurrences) ->
            occurrences.flatMap { it.artists }
                .distinctBy { it.id ?: "credit:${it.name.trim().lowercase()}" }
        }
    return summaries.map { summary ->
        summary.copy(items = summary.items.map { item ->
            if (item is SongItem) {
                val merged = (item.artists + knownArtists[item.id].orEmpty())
                    .distinctBy { it.id ?: "credit:${it.name.trim().lowercase()}" }
                if (merged.size > item.artists.size) item.copy(artists = merged) else item
            } else item
        })
    }
}

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

/**
 * Only YouTube Music's explicitly labelled video/podcast shelves receive the
 * direct-video marker. Top results and ordinary Songs remain AUDIO by default.
 */
internal fun markExplicitVideoShelves(summaries: List<SearchSummary>): List<SearchSummary> =
    summaries.map { summary ->
        val title = summary.title.trim().lowercase()
        val isVideoShelf = listOf(
            "videos", "music videos", "video", "клипы", "видео",
            "podcasts", "podcast episodes", "episodes", "подкасты", "эпизоды",
        ).any(title::startsWith)
        if (isVideoShelf) {
            summary.copy(items = summary.items.map { item ->
                if (item is SongItem) item.copy(selectedFromVideoResults = true) else item
            })
        } else {
            summary
        }
    }


data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {

    /**
     * Search Summary may carry only a lead artist while Metrolist-style Songs
     * search contains the full linked credits. Enrich *only the same video ID*;
     * never confuse this with Artist search recommendations or substring name
     * matching (different recordings often share a title).
     */
    fun withFilteredSongCredits(filteredSongs: List<SongItem>): SearchSummaryPage {
        if (filteredSongs.isEmpty()) return this
        val creditsById = filteredSongs.groupBy { it.id }.mapValues { (_, songs) ->
            songs.flatMap { it.artists }
                .distinctBy { it.id ?: "credit:${it.name.trim().lowercase()}" }
        }
        return copy(summaries = summaries.map { summary ->
            summary.copy(items = summary.items.map { item ->
                if (item !is SongItem) item else {
                    val credited = (item.artists + creditsById[item.id].orEmpty())
                        .distinctBy { it.id ?: "credit:${it.name.trim().lowercase()}" }
                    if (credited.size > item.artists.size) item.copy(artists = credited) else item
                }
            })
        })
    }

    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterVideo(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items = s.items.filterVideo().ifEmpty { return@mapNotNull null },
                    )
                },
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = extractSearchSongArtists(renderer.subtitle.runs.orEmpty()),
                        album =
                            renderer.subtitle.runs?.firstOrNull {
                                it.navigationEndpoint?.browseEndpoint?.isAlbumEndpoint == true
                            }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration = extractSearchSongDuration(renderer.subtitle.runs.orEmpty()),
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        endpoint = renderer.onTap.watchEndpoint,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MIX" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            subtitle?.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        year = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id =
                            renderer.onTap.browseEndpoint.browseId
                                .removePrefix("VL"),
                        title =
                            renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author =
                            Artist(
                                id = null,
                                name = renderer.subtitle.runs?.joinToString { it.text } ?: return null,
                            ),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        radioEndpoint = null,
                    )
                }

                else -> null
            }
        }

        fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer,
            fallbackArtists: List<Artist> = emptyList(),
        ): YTItem? {
            val secondaryLine =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    .orEmpty()
            return when {
                renderer.isSong -> {
                    val metadataRuns = renderer.flexColumns.drop(1).flatMap {
                        it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty()
                    }
                    SongItem(
                        // Podcast/video rows can carry a valid watch endpoint
                        // without playlistItemData; never confuse a playlist's
                        // setVideoId token with the playable YouTube videoId.
                        id = renderer.playlistItemData?.videoId
                            ?: renderer.navigationEndpoint?.anyWatchEndpoint?.videoId
                            ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                                ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.anyWatchEndpoint?.videoId
                            ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists = extractSearchSongArtists(metadataRuns).ifEmpty { fallbackArtists },
                        album = metadataRuns.firstOrNull {
                            it.navigationEndpoint?.browseEndpoint?.isAlbumEndpoint == true
                        }?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                            )
                        },
                        duration = extractSearchSongDuration(metadataRuns),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        endpoint = renderer.navigationEndpoint?.anyWatchEndpoint
                            ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                                ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.menu.menuRenderer.items
                                .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        playlistId =
                            renderer.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint
                                ?.playlistId
                                ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            secondaryLine.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        year =
                            secondaryLine
                                .getOrNull(2)
                                ?.firstOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.isPlaylist -> {
                    PlaylistItem(
                        id =
                            renderer.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId
                                ?.removePrefix("VL") ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        author =
                            secondaryLine.getOrNull(1)?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        songCountText =
                            renderer.flexColumns
                                .getOrNull(1)
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.lastOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                        shuffleEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.menu.menuRenderer.items
                                .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                else -> null
            }
        }
    }
}
