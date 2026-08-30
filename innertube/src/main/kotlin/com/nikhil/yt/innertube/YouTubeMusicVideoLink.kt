/**
 * Capsule MUSIC
 *
 * YouTube Music official-video matcher.
 *
 * This deliberately searches YouTube Music's VIDEO result surface, not normal
 * YouTube. Only MUSIC_VIDEO_TYPE_OMV candidates are accepted. UGC, ATV,
 * lyrics, live performances and unrelated variants are rejected.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.SearchResponse
import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class YouTubeMusicVideoLink(
    val videoId: String,
    val musicVideoType: String,
    val title: String,
    val score: Int,
)

object YouTubeMusicVideoLinkResolver {
    /*
     * YouTube Music "Videos" filter. This is the same WEB_REMIX search surface
     * used by YouTube Music itself; results carry musicVideoType on the watch
     * endpoint so we can reject UGC/ATV before playback.
     */
    private const val VIDEO_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"

    private val mutex = Mutex()
    private val innerTube = InnerTube()

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

        mutex.withLock {
            syncSession()

            val query =
                buildString {
                    append(sourceTitle)
                    cleanArtists.firstOrNull()?.let {
                        append(' ')
                        append(it)
                    }
                }

            val response =
                innerTube
                    .search(
                        client = WEB_REMIX,
                        query = query,
                        params = VIDEO_FILTER,
                    )
                    .body<SearchResponse>()

            val renderers =
                response.contents
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

            val sourceTitleNorm = normalizeTitle(sourceTitle)
            val sourceArtistNorms =
                cleanArtists
                    .map(::normalizeText)
                    .filter(String::isNotBlank)

            val candidates =
                renderers
                    .mapNotNull { renderer ->
                        candidateFromRenderer(
                            renderer = renderer,
                            sourceMediaId = sourceId,
                            sourceTitle = sourceTitle,
                            sourceTitleNorm = sourceTitleNorm,
                            sourceArtistNorms = sourceArtistNorms,
                            sourceDurationSeconds = durationSeconds,
                        )
                    }
                    .sortedByDescending { it.score }

            val best =
                candidates.firstOrNull()
                    ?: throw IllegalStateException(
                        "YouTube Music did not find a matching official video",
                    )

            YouTubeMusicVideoLink(
                videoId = best.videoId,
                musicVideoType = MUSIC_VIDEO_TYPE_OMV,
                title = best.title,
                score = best.score,
            )
        }
    }

    private data class Candidate(
        val videoId: String,
        val title: String,
        val score: Int,
    )

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

        /*
         * An ATV id can sometimes also appear in a mixed renderer. Requiring a
         * different id prevents the button from "switching" to the same audio
         * upload while pretending video mode succeeded.
         */
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
        if (candidateTitleNorm.isBlank()) return null

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

        var score = 0

        score +=
            when {
                candidateTitleNorm == sourceTitleNorm -> 120
                candidateTitleNorm.contains(sourceTitleNorm) ||
                    sourceTitleNorm.contains(candidateTitleNorm) -> 82
                titleTokenOverlap(sourceTitleNorm, candidateTitleNorm) >= 0.75 -> 65
                titleTokenOverlap(sourceTitleNorm, candidateTitleNorm) >= 0.55 -> 42
                else -> return null
            }

        if (sourceArtistNorms.isNotEmpty()) {
            val artistMatched =
                sourceArtistNorms.any { artist ->
                    artist.length >= 2 && secondaryNorm.contains(artist)
                }

            if (!artistMatched) return null
            score += 55
        }

        val candidateDuration = extractDurationSeconds(allSecondaryText)
        val sourceDuration =
            sourceDurationSeconds
                ?.takeIf { it > 0 }

        if (sourceDuration != null && candidateDuration != null) {
            val delta = abs(sourceDuration - candidateDuration)
            score +=
                when {
                    delta <= 5 -> 36
                    delta <= 12 -> 28
                    delta <= 25 -> 15
                    delta <= 45 -> 4
                    else -> return null
                }
        }

        /*
         * OMV is already authoritative, but a meaningful threshold protects
         * against a same-artist video with a weakly similar title.
         */
        if (score < 120) return null

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
        val source = normalizeText(sourceTitle)
        val candidate = normalizeText(candidateTitle)

        val rejectTokens =
            listOf(
                " live ",
                " concert ",
                " performance ",
                " acoustic ",
                " cover ",
                " karaoke ",
                " lyric ",
                " lyrics ",
                " visualizer ",
                " slowed ",
                " reverb ",
                " sped up ",
                " nightcore ",
                " remix ",
            )

        val sourcePadded = " $source "
        val candidatePadded = " $candidate "

        return rejectTokens.any { token ->
            candidatePadded.contains(token) &&
                !sourcePadded.contains(token)
        }
    }

    private fun normalizeTitle(value: String): String {
        return normalizeText(value)
            .replace(Regex("\\bofficial\\s+music\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\s+video\\b"), " ")
            .replace(Regex("\\bmusic\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeText(value: String): String {
        return value
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun titleTokenOverlap(
        left: String,
        right: String,
    ): Double {
        val a = left.split(' ').filter { it.length > 1 }.toSet()
        val b = right.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0

        return a.intersect(b).size.toDouble() /
            a.union(b).size.toDouble()
    }

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
