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
    // Compile once, not once for every metadata run on every shelf.
    private val artistDurationPattern = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
    private val itemTypeLabels = setOf(
        "композиция", "песня", "песни", "музыка", "видео", "клип",
        "альбом", "плейлист", "подкаст", "эпизод", "исполнитель",
        "song", "songs", "track", "music", "music video", "video",
        "album", "playlist", "podcast", "episode", "artist",
        "official audio", "audio", "recording",
    )
    private val artistJoinerTokens = setOf(
        ",", "&", "/", ";", "feat.", "ft.", "featuring", "и", "and",
    )
    private val artistSeparatorTokens = artistJoinerTokens +
        setOf("•", "·", "|")
    private val metadataGroupSeparators = setOf("•", "·", "|")
    // These are engagement statistics, NOT contributors. A Top result sometimes
    // contains only this label and no artist link (especially in ru-RU).
    // Require a leading count so real names like "Million Views" survive.
    private val audienceStatisticPattern = Regex(
        """(?iu)^\d[\d\s.,\u00a0\u202f]*(?:k|m|b|тыс\.?|млн\.?|млрд\.?|тысяч[а-яё]*|миллион[а-яё]*|миллиард[а-яё]*|thousand|million|billion)?\s*(?:monthly\s+)?(?:views?|plays?|streams?|listeners?|subscribers?|просмотр[а-яё]*|прослушиван[а-яё]*|слушател[а-яё]*|подписчик[а-яё]*)(?:\s+(?:за месяц|в месяц|per month))?$"""
    )

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
                    if (name.isArtistSeparator() || name.isMetadataTypeLabel() ||
                        name.isArtistDuration() || name.isAudienceStatistic()
                    ) return@mapNotNull null
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
            .let(::deduplicateArtistCredits)

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
        val groups = runs.orEmpty().splitSongMetadataGroups()
        val hasLinkedArtist = groups.any { group -> group.any anyRun@{ run ->
            val browse = run.navigationEndpoint?.browseEndpoint ?: return@anyRun false
            val pageType = browse.browseEndpointContextSupportedConfigs
                ?.browseEndpointContextMusicConfig?.pageType
            pageType == MUSIC_PAGE_TYPE_ARTIST || pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                pageType == MUSIC_PAGE_TYPE_USER_CHANNEL ||
                (pageType == null && browse.browseId.startsWith("UC"))
        } }
        // Without linked performers the first *plausible* metadata group is
        // an artist. Song badges, durations and view/play counters can precede
        // it; they must never be emitted as artists or block the real credit.
        val unlinkedArtistGroup = if (hasLinkedArtist) -1 else groups.indexOfFirst { group ->
            !group.joinToString(separator = "") { it.text }.isAudienceStatistic() &&
                group.any { run ->
                    run.navigationEndpoint == null &&
                        run.text.isNotBlank() &&
                        !run.text.isArtistSeparator() &&
                        !run.text.isMetadataTypeLabel() &&
                        !run.text.isArtistDuration() &&
                        !run.text.isAudienceStatistic()
                }
        }
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
                            !name.isMetadataTypeLabel() && !name.isAudienceStatistic() &&
                            (group.getOrNull(index - 1)?.text?.isArtistJoiner() == true ||
                                group.getOrNull(index + 1)?.text?.isArtistJoiner() == true)
                    }
                }
            } else if (!hasLinkedArtist && groupIndex == unlinkedArtistGroup) {
                // Text-only credits are allowed, but never statistics or
                // labels; an unknown later album group is not a safe fallback.
                group.filter { run ->
                    run.navigationEndpoint == null &&
                        run.text.isNotBlank() && !run.text.isArtistDuration() &&
                        !run.text.isArtistSeparator() &&
                        !run.text.isMetadataTypeLabel() && !run.text.isAudienceStatistic()
                }
            } else emptyList()
            all += extractArtists(creditRuns)
        }
        return deduplicateArtistCredits(all)
    }

    /** Collapse linked/unlinked copies of a credit without changing shelf order. */
    fun deduplicateArtistCredits(artists: List<Artist>): List<Artist> {
        val unique = linkedMapOf<String, Artist>()
        artists.forEach { artist ->
            if (artist.id.isNullOrBlank() && isAudienceStatisticLabel(artist.name)) return@forEach
            val key = artist.name.trim().lowercase(java.util.Locale.ROOT)
            val previous = unique[key]
            if (previous == null || (previous.id == null && artist.id != null)) {
                unique[key] = artist
            }
        }
        return unique.values.toList()
    }

    // YouTube alternates " • " and other complete delimiter runs across locales.
    // This is intentionally local to SONG metadata, not the shared Runs parser.
    private fun List<Run>.splitSongMetadataGroups(): List<List<Run>> {
        val groups = mutableListOf<List<Run>>()
        var current = mutableListOf<Run>()
        for (run in this) {
            if (run.navigationEndpoint == null && run.text.trim() in metadataGroupSeparators) {
                groups += current
                current = mutableListOf()
            } else {
                current += run
            }
        }
        groups += current
        return groups
    }

    /** Share one counter detector across Innertube and old cached playback credits. */
    fun isAudienceStatisticLabel(text: String): Boolean {
        val normalized = text
            .filterNot {
                it == '\u200B' || it == '\u200C' || it == '\u200D' ||
                    it == '\u200E' || it == '\u200F' || it == '\uFEFF'
            }
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim().trimStart('~', '≈').trim()
        return audienceStatisticPattern.matches(normalized)
    }

    private fun String.isAudienceStatistic(): Boolean = isAudienceStatisticLabel(this)

    private fun String.isMetadataTypeLabel(): Boolean =
        trim().lowercase(java.util.Locale.ROOT) in itemTypeLabels

    private fun String.isArtistDuration(): Boolean =
        artistDurationPattern.matches(trim())

    private fun String.isArtistJoiner(): Boolean {
        val value = trim().lowercase()
        return value in artistJoinerTokens ||
            (value.isNotEmpty() && value.all { it == ',' || it == '&' || it == '/' || it == ';' })
    }

    private fun String.isArtistSeparator(): Boolean {
        val normalized = trim().lowercase()
        if (normalized in artistSeparatorTokens) {
            return true
        }
        return normalized.isNotEmpty() &&
            normalized.all { it == '•' || it == '·' || it == ',' || it == '&' || it == '/' || it == ';' || it == '|' }
    }
}
