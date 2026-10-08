/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.nikhil.yt.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.nikhil.yt.innertube.models.Run

object PageHelper {
    fun extractRuns(columns: List<FlexColumn>, typeLike: String): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs = column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                ?: continue

            for (run in runs) {
                val typeStr = run.navigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
                    ?: run.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                    ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    /**
     * Canonical artist-credit parser used by every Innertube surface.
     *
     * YouTube does not guarantee a browse id for every credited performer. A guest artist can be a
     * plain text run while the primary artist is linked. Dropping that run makes the same track
     * show a different set of performers in Home, search, playlists and the player.
     *
     * Callers should pass the artist-only run group (for mixed subtitles, the first bullet-separated
     * group). Linked album/playlist runs are rejected here; unlinked non-separator text is preserved
     * as a visible, non-navigable credit.
     */
    fun extractArtists(runs: List<Run>?): List<Artist> =
        runs.orEmpty()
            .mapNotNull { run ->
                val name = run.text.trim()
                if (name.isBlank() || name.isArtistSeparator()) return@mapNotNull null

                val endpoint = run.navigationEndpoint?.browseEndpoint
                if (endpoint == null) {
                    return@mapNotNull Artist(name = name, id = null)
                }

                val pageType =
                    endpoint.browseEndpointContextSupportedConfigs
                        ?.browseEndpointContextMusicConfig
                        ?.pageType
                val isArtistEndpoint =
                    pageType == MUSIC_PAGE_TYPE_ARTIST ||
                        pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                        pageType == MUSIC_PAGE_TYPE_USER_CHANNEL ||
                        (pageType == null && endpoint.browseId.startsWith("UC"))

                if (!isArtistEndpoint) {
                    null
                } else {
                    Artist(name = name, id = endpoint.browseId)
                }
            }
            .distinctBy { artist ->
                artist.id ?: "name:${artist.name.lowercase()}"
            }

    private fun String.isArtistSeparator(): Boolean {
        val normalized = trim().lowercase()
        if (
            normalized in
                setOf(
                    "•", "·", ",", "&", "/", ";", "|",
                    "feat.", "ft.", "featuring",
                    "и", "and",
                )
        ) {
            return true
        }
        return normalized.isNotEmpty() &&
            normalized.all { it == '•' || it == '·' || it == ',' || it == '&' || it == '/' || it == ';' || it == '|' }
    }
}
