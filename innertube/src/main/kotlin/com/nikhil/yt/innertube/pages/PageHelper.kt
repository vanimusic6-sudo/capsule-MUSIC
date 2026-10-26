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
import com.nikhil.yt.innertube.models.splitBySeparator

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
                if (name.isBlank()) return@mapNotNull null

                val endpoint = run.navigationEndpoint?.browseEndpoint
                if (endpoint == null) {
                    if (name.isArtistSeparator()) return@mapNotNull null
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

    /**
     * One canonical parser for SONG credits in mixed YouTube metadata.
     * Handles all flex columns and all bullet groups; album/playlist links,
     * durations and titles are excluded. A text-only guest next to a linked
     * performer is preserved without manufacturing unrelated artist names.
     *
     * The same logic is shared by top result, filtered Songs, suggestions,
     * albums and playlists. Pure artist-only groups still use extractArtists.
     */
    fun extractSongArtists(runs: List<Run>?): List<Artist> {
        val groups = runs.orEmpty().splitBySeparator()
        val all = mutableListOf<Artist>()
        groups.forEachIndexed { groupIndex, group ->
            val linked = group.indices.filter { index ->
                val endpoint = group[index].navigationEndpoint?.browseEndpoint ?: return@filter false
                val pageType = endpoint.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig?.pageType
                pageType == MUSIC_PAGE_TYPE_ARTIST ||
                    pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                    pageType == MUSIC_PAGE_TYPE_USER_CHANNEL ||
                    (pageType == null && endpoint.browseId.startsWith("UC"))
            }.toSet()

            val creditRuns = if (linked.isNotEmpty()) {
                group.filterIndexed { index, run ->
                    if (index in linked) true
                    else if (run.navigationEndpoint != null) false
                    else {
                        val name = run.text.trim()
                        name.isNotEmpty() && !name.isArtistDuration() &&
                            (group.getOrNull(index - 1)?.text?.isArtistJoiner() == true ||
                                group.getOrNull(index + 1)?.text?.isArtistJoiner() == true)
                    }
                }
            } else if (groupIndex == 0) {
                // Some unauthenticated renderers omit ALL browse endpoints.
                // Consider only the very first metadata group, never later
                // album/genre/duration groups, to avoid false collaborators.
                group.filter { run ->
                    run.navigationEndpoint == null &&
                        run.text.isNotBlank() && !run.text.isArtistDuration() &&
                        !run.text.isArtistSeparator()
                }
            } else emptyList()
            all += extractArtists(creditRuns)
        }
        return all.distinctBy { it.id ?: "credit:${it.name.trim().lowercase()}" }
    }

    private fun String.isArtistDuration(): Boolean =
        Regex("""\\d{1,2}:\\d{2}(?::\\d{2})?""").matches(trim())

    private fun String.isArtistJoiner(): Boolean {
        val value = trim().lowercase()
        return value in setOf(",", "&", "/", ";", "feat.", "ft.", "featuring", "и", "and") ||
            (value.isNotEmpty() && value.all { it == ',' || it == '&' || it == '/' || it == ';' })
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
