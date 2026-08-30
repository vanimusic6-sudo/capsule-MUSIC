/**
 * Capsule MUSIC
 *
 * Strict YouTube Music official-video matcher.
 *
 * Only the YouTube Music Videos surface and MUSIC_VIDEO_TYPE_OMV are accepted.
 * The matcher deliberately prefers "no video" over a wrong live/fan/visualizer
 * result. Positive and negative results are cached to avoid repeated searches.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.SearchResponse
import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class YouTubeMusicVideoLink(
    val videoId: String,
    val musicVideoType: String,
    val title: String,
    val score: Int,
)

object YouTubeMusicVideoLinkResolver {
    private const val VIDEO_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val POSITIVE_CACHE_MS = 12 * 60 * 60 * 1000L
    private const val NEGATIVE_CACHE_MS = 20 * 60 * 1000L

    private data class CacheEntry(
        val link: YouTubeMusicVideoLink?,
        val expiresAtMs: Long,
    )

    private data class Candidate(
        val videoId: String,
        val title: String,
        val score: Int,
    )

    private val mutex = Mutex()
    private val innerTube = InnerTube()
    private val matchCache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolve(
        sourceMediaId: String,
        title: String,
        artists: List<String>,
        durationSeconds: Int?,
    ): Result<YouTubeMusicVideoLink> = runCatching {
        val sourceId = sourceMediaId.trim()
        val sourceTitle = title.trim()
        val cleanArtists = artists.map { it.trim() }.filter { it.isNotBlank() }

        require(sourceId.isNotBlank()) { "Missing YouTube Music track id" }
        require(sourceTitle.isNotBlank()) { "Missing song title" }

        cachedOrNull(sourceId)?.let { cached ->
            return@runCatching cached
        }
        if (isNegativeCached(sourceId)) {
            throw IllegalStateException("Official music video is unavailable for this song")
        }

        mutex.withLock {
            cachedOrNull(sourceId)?.let { return@withLock it }
            if (isNegativeCached(sourceId)) {
                throw IllegalStateException("Official music video is unavailable for this song")
            }

            syncSession()

            val sourceTitleNorm = normalizeTitle(sourceTitle)
            val sourceArtistNorms =
                cleanArtists
                    .map(::normalizeText)
                    .filter(String::isNotBlank)
                    .distinct()

            val queries =
                buildList {
                    val mainArtist = cleanArtists.firstOrNull()
                    if (mainArtist != null) {
                        add("$mainArtist $sourceTitle official music video")
                        add("$mainArtist $sourceTitle")
                    } else {
                        add("$sourceTitle official music video")
                        add(sourceTitle)
                    }
                }.distinct()

            val candidatesById = linkedMapOf<String, Candidate>()

            for (query in queries) {
                val renderers = searchVideoRenderers(query)
                for (renderer in renderers) {
                    val candidate =
                        candidateFromRenderer(
                            renderer = renderer,
                            sourceMediaId = sourceId,
                            sourceTitle = sourceTitle,
                            sourceTitleNorm = sourceTitleNorm,
                            sourceArtistNorms = sourceArtistNorms,
                            sourceDurationSeconds = durationSeconds,
                        ) ?: continue

                    val previous = candidatesById[candidate.videoId]
                    if (previous == null || candidate.score > previous.score) {
                        candidatesById[candidate.videoId] = candidate
                    }
                }
            }

            val best = candidatesById.values.maxByOrNull { it.score }
            if (best == null) {
                matchCache[sourceId] =
                    CacheEntry(
                        link = null,
                        expiresAtMs = System.currentTimeMillis() + NEGATIVE_CACHE_MS,
                    )
                throw IllegalStateException("YouTube Music did not find a trustworthy official clip")
            }

            val link =
                YouTubeMusicVideoLink(
                    videoId = best.videoId,
                    musicVideoType = MUSIC_VIDEO_TYPE_OMV,
                    title = best.title,
                    score = best.score,
                )

            matchCache[sourceId] =
                CacheEntry(
                    link = link,
                    expiresAtMs = System.currentTimeMillis() + POSITIVE_CACHE_MS,
                )

            link
        }
    }

    private suspend fun searchVideoRenderers(
        query: String,
    ): List<MusicResponsiveListItemRenderer> {
        val response =
            innerTube
                .search(
                    client = WEB_REMIX,
                    query = query,
                    params = VIDEO_FILTER,
                )
                .body<SearchResponse>()

        return response.contents
            ?.tabbedSearchResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            .orEmpty()
            .flatMap { section ->
                section.musicShelfRenderer
                    ?.contents
                    .orEmpty()
                    .mapNotNull { it.musicResponsiveListItemRenderer }
            }
    }

    private fun candidateFromRenderer(
        renderer: MusicResponsiveListItemRenderer,
        sourceMediaId: String,
        sourceTitle: String,
        sourceTitleNorm: String,
        sourceArtistNorms: List<String>,
        sourceDurationSeconds: Int?,
    ): Candidate? {
        val endpoint =
            renderer.navigationEndpoint?.anyWatchEndpoint
                ?: renderer.overlay
                    ?.musicItemThumbnailOverlayRenderer
                    ?.content
                    ?.musicPlayButtonRenderer
                    ?.playNavigationEndpoint
                    ?.anyWatchEndpoint
                ?: return null

        val videoType =
            endpoint.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType
                ?: return null

        if (videoType != MUSIC_VIDEO_TYPE_OMV) return null

        val videoId =
            endpoint.videoId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: renderer.playlistItemData?.videoId?.trim()?.takeIf { it.isNotBlank() }
                ?: return null

        if (videoId == sourceMediaId) return null

        val candidateTitle =
            renderer.flexColumns
                .firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
                ?.text
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        if (isRejectedVariant(sourceTitle, candidateTitle)) return null

        val candidateTitleNorm = normalizeTitle(candidateTitle)
        if (candidateTitleNorm.isBlank() || sourceTitleNorm.isBlank()) return null

        val allSecondaryText =
            renderer.flexColumns
                .drop(1)
                .flatMap {
                    it.musicResponsiveListItemFlexColumnRenderer
                        .text
                        ?.runs
                        .orEmpty()
                }
                .joinToString(" ") { it.text }

        val secondaryNorm = normalizeText(allSecondaryText)

        val exactTitle = candidateTitleNorm == sourceTitleNorm
        val artistPrefixedTitle =
            sourceArtistNorms.any { artist ->
                candidateTitleNorm == "$artist $sourceTitleNorm" ||
                    candidateTitleNorm == "$sourceTitleNorm $artist"
            }

        /*
         * This is intentionally strict. Similar titles are where most of the
         * "random artist video" false positives came from in the first version.
         */
        if (!exactTitle && !artistPrefixedTitle) return null

        if (sourceArtistNorms.isNotEmpty()) {
            val artistMatched =
                sourceArtistNorms.any { artist ->
                    artist.length >= 2 && containsWholePhrase(secondaryNorm, artist)
                }
            if (!artistMatched) return null
        }

        var score = if (exactTitle) 230 else 205
        score += 90 // MUSIC_VIDEO_TYPE_OMV + artist match

        val rawTitleNorm = normalizeText(candidateTitle)
        if (
            rawTitleNorm.contains("official music video") ||
            rawTitleNorm.contains("official video")
        ) {
            score += 45
        }

        val candidateDuration = extractDurationSeconds(allSecondaryText)
        val sourceDuration = sourceDurationSeconds?.takeIf { it > 0 }

        if (sourceDuration != null && candidateDuration != null) {
            val delta = abs(sourceDuration - candidateDuration)
            score +=
                when {
                    delta <= 5 -> 45
                    delta <= 12 -> 35
                    delta <= 25 -> 22
                    delta <= 45 -> 10
                    delta <= 65 && exactTitle -> 2
                    else -> return null
                }
        }

        if (score < 300) return null

        return Candidate(
            videoId = videoId,
            title = candidateTitle,
            score = score,
        )
    }

    private fun isRejectedVariant(
        sourceTitle: String,
        candidateTitle: String,
    ): Boolean {
        val source = " ${normalizeText(sourceTitle)} "
        val candidate = " ${normalizeText(candidateTitle)} "

        val contextualRejects =
            listOf(
                " live ",
                " concert ",
                " performance ",
                " session ",
                " acoustic ",
                " cover ",
                " karaoke ",
                " lyric ",
                " lyrics ",
                " visualizer ",
                " animated video ",
                " dance video ",
                " dance practice ",
                " slowed ",
                " reverb ",
                " sped up ",
                " nightcore ",
                " remix ",
                " edit ",
                " fanmade ",
                " fan made ",
                " amv ",
                " reaction ",
                " interview ",
                " behind the scenes ",
                " making of ",
                " teaser ",
                " trailer ",
                " vertical video ",
                " shorts ",
                " challenge ",
            )

        if (
            contextualRejects.any { token ->
                candidate.contains(token) && !source.contains(token)
            }
        ) {
            return true
        }

        val alwaysReject =
            listOf(
                " official audio ",
                " audio only ",
                " topic audio ",
            )

        return alwaysReject.any { token -> candidate.contains(token) }
    }

    private fun normalizeTitle(value: String): String =
        normalizeText(value)
            .replace(Regex("\\bofficial\\s+music\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\s+video\\b"), " ")
            .replace(Regex("\\bmusic\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\b"), " ")
            .replace(Regex("\\bremaster(?:ed)?(?:\\s+\\d{4})?\\b"), " ")
            .replace(Regex("\\balbum\\s+version\\b"), " ")
            .replace(Regex("\\bsingle\\s+version\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeText(value: String): String =
        value
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun containsWholePhrase(
        haystack: String,
        needle: String,
    ): Boolean =
        " $haystack ".contains(" $needle ") || haystack == needle

    private fun extractDurationSeconds(text: String): Int? {
        val match =
            Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
                .find(text)
                ?: return null

        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        val third = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()

        return if (third == null) {
            first * 60 + second
        } else {
            first * 3600 + second * 60 + third
        }
    }

    private fun cachedOrNull(sourceId: String): YouTubeMusicVideoLink? {
        val entry = matchCache[sourceId] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return null
        }
        return entry.link
    }

    private fun isNegativeCached(sourceId: String): Boolean {
        val entry = matchCache[sourceId] ?: return false
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            matchCache.remove(sourceId)
            return false
        }
        return entry.link == null
    }

    private fun syncSession() {
        innerTube.locale = YouTube.locale
        innerTube.authState = YouTube.authState
        innerTube.useLoginForBrowse = YouTube.useLoginForBrowse

        val proxy = YouTube.proxy
        if (innerTube.proxy != proxy) {
            innerTube.proxy = proxy
        }
    }
}
